# Java Thread QoS — техническое задание на отдельный open-source проект

**Рабочее название проекта:** `java-thread-qos`  
**Рабочий Maven artifactId:** `thread-qos`  
**Язык:** Java  
**Минимальная версия Java:** 25  
**Native interop:** только Foreign Function & Memory API (`java.lang.foreign`)  
**Лицензия:** Apache License 2.0  
**Основной канал публикации:** Maven Central  
**Дополнительные каналы:** GitHub Releases; GitHub Packages — только опционально, например для snapshots

---

## 1. Цель проекта

Создать небольшую независимую Java-библиотеку, позволяющую задавать Quality of Service / performance intent **текущему Java platform thread** через нативные scheduler API операционной системы.

Основной пользовательский сценарий:

```java
Thread.ofPlatform()
        .name("market-data")
        .start(ThreadQos.wrap(
                QosClass.USER_INTERACTIVE,
                this::marketDataLoop
        ));
```

или:

```java
ThreadQos.set(QosClass.USER_INTERACTIVE);
```

Библиотека должна предоставлять единый Java API, а внутри использовать наиболее подходящие механизмы конкретной ОС:

- macOS — pthread QoS;
- Windows — Thread Power Throttling / QoS и thread priority;
- Linux — utilization clamping (`uclamp`) через `sched_setattr`;
- неподдерживаемые ОС — безопасный noop.

Проект предназначен прежде всего для:

- low-latency Java applications;
- trading infrastructure;
- market-data processing;
- order execution;
- media / realtime-ish workloads;
- high-performance servers;
- приложений, где фоновые потоки желательно явно отличать от latency-sensitive потоков.

Проект **не должен обещать hard realtime guarantees** и не должен позиционировать QoS как CPU affinity.

---

# 2. Основные принципы

## 2.1. Без JNI/JNA

Запрещено использовать:

- JNI;
- JNA;
- JNR;
- JavaCPP;
- отдельные C/C++ wrapper libraries;
- `.dylib`;
- `.so`;
- `.dll`, поставляемые самим проектом.

Все системные вызовы должны выполняться через стандартный Java FFM API:

```java
java.lang.foreign
```

Допустимы системные библиотеки, уже входящие в ОС:

- macOS system libc/libpthread;
- Windows `Kernel32.dll`;
- Linux libc и kernel syscalls.

---

## 2.2. Single JAR

Основной релизный artifact должен быть одним JAR:

```text
thread-qos-X.Y.Z.jar
```

Не делать отдельные:

```text
thread-qos-macos.jar
thread-qos-linux.jar
thread-qos-windows.jar
```

если для этого нет объективной необходимости.

FFM позволяет держать platform-specific Java implementations в одном JAR без доставки native binaries.

---

## 2.3. Current-thread semantics

Основной API работает только с **текущим потоком**.

То есть:

```java
ThreadQos.set(...)
```

всегда означает:

```text
изменить scheduling/QoS текущего native platform thread
```

Не требуется API вида:

```java
ThreadQos.set(otherThread, ...)
```

для первой версии.

Это существенно упрощает:

- безопасность;
- native handle management;
- cross-platform semantics;
- lifecycle;
- работу с pthread/Win32/Linux scheduler API.

---

## 2.4. Только platform threads

QoS запрещено менять из Java virtual thread.

Перед нативным вызовом:

```java
if (Thread.currentThread().isVirtual()) {
    throw new IllegalStateException(
        "Thread QoS can only be changed from a Java platform thread"
    );
}
```

Причина: virtual thread исполняется на shared carrier thread. Изменение native QoS из virtual thread потенциально изменит параметры carrier thread, который затем будет исполнять другие virtual threads.

Для unsupported/noop platform допускается выбрать единое поведение:

- предпочтительно всё равно валидировать virtual thread и явно запрещать вызов;
- это делает контракт API одинаковым на всех ОС и предотвращает platform-dependent bugs.

---

# 3. Публичный API

## 3.1. QoS enum

```java
public enum QosClass {
    USER_INTERACTIVE,
    USER_INITIATED,
    DEFAULT,
    UTILITY,
    BACKGROUND
}
```

Семантика:

### `USER_INTERACTIVE`

Максимально latency-sensitive foreground/critical work.

Примеры:

- order execution;
- market-data critical path;
- event dispatch с жесткими latency expectations.

Не является real-time scheduling policy.

### `USER_INITIATED`

Высокий приоритет производительности, но менее агрессивный, чем `USER_INTERACTIVE`.

Примеры:

- trading strategy calculation;
- synchronous user-triggered computation;
- time-sensitive data transformation.

### `DEFAULT`

Вернуть поток к обычному/default performance intent библиотеки.

Не гарантируется восстановление всех scheduler attributes, которые существовали до первого вызова библиотеки, если ОС не предоставляет надёжный round-trip механизм.

В реализации следует стремиться к максимально корректному reset.

### `UTILITY`

Фоновая полезная работа, которая не должна конкурировать с latency-sensitive потоками.

Примеры:

- batching;
- CSV persistence;
- periodic statistics;
- report generation.

### `BACKGROUND`

Низкоприоритетная background работа.

Примеры:

- housekeeping;
- cache cleanup;
- non-urgent maintenance;
- secondary telemetry.

---

## 3.2. `ThreadQos`

Предпочтительный API:

```java
public final class ThreadQos {

    private ThreadQos() {
    }

    public static boolean isSupported();

    public static QosCapabilities capabilities();

    public static void set(QosClass qosClass);

    public static Runnable wrap(
            QosClass qosClass,
            Runnable task
    );

    public static <V> Callable<V> wrap(
            QosClass qosClass,
            Callable<V> task
    );
}
```

Дополнительно можно добавить:

```java
public static Optional<QosClass> current();
```

только если read-back можно реализовать достаточно надёжно на каждой поддерживаемой платформе.

Если `current()` не может возвращать корректную portable abstraction, лучше не включать его в `1.0.0`, чем возвращать misleading result.

---

# 4. Capability API

Не ограничиваться одним boolean.

Добавить immutable record:

```java
public record QosCapabilities(
        OperatingSystem operatingSystem,
        boolean qosSupported,
        boolean nativeReadbackSupported,
        boolean performanceHintSupported,
        boolean priorityAdjustmentSupported
) {
}
```

`OperatingSystem`:

```java
public enum OperatingSystem {
    MACOS,
    WINDOWS,
    LINUX,
    OTHER
}
```

Допустимо упростить структуру, если после реализации выяснится, что поля не дают практической ценности.

Минимальное требование:

```java
ThreadQos.isSupported()
```

должно корректно показывать, выполняет ли provider реальные native операции.

---

# 5. Internal architecture

Использовать Strategy/Provider abstraction.

```java
interface ThreadQosProvider {

    boolean isSupported();

    QosCapabilities capabilities();

    void set(QosClass qosClass);
}
```

Реализации:

```text
MacOsThreadQosProvider
WindowsThreadQosProvider
LinuxThreadQosProvider
NoOpThreadQosProvider
```

Provider выбирается один раз:

```java
private static final ThreadQosProvider PROVIDER =
        ThreadQosProviderFactory.create();
```

Не выполнять:

```java
System.getProperty("os.name")
```

при каждом `set()`.

---

# 6. Предлагаемая структура packages

Рабочий вариант:

```text
src/main/java/
  module-info.java

  .../threadqos/
    ThreadQos.java
    QosClass.java
    QosCapabilities.java
    OperatingSystem.java

  .../threadqos/internal/
    ThreadQosProvider.java
    ThreadQosProviderFactory.java
    FfmSupport.java

  .../threadqos/internal/macos/
    MacOsThreadQosProvider.java
    MacOsQosNative.java

  .../threadqos/internal/windows/
    WindowsThreadQosProvider.java
    WindowsQosNative.java

  .../threadqos/internal/linux/
    LinuxThreadQosProvider.java
    LinuxQosNative.java
    LinuxSchedAttr.java

  .../threadqos/internal/noop/
    NoOpThreadQosProvider.java
```

Конкретный root package определить после выбора Maven `groupId`.

Не переусложнять package tree, если код получается маленьким.

---

# 7. macOS implementation

## 7.1. Native API

Использовать:

```c
int pthread_set_qos_class_self_np(
    qos_class_t qos_class,
    int relative_priority
);
```

Функция доступна начиная с macOS 10.10.

Она меняет requested QoS **current thread**.

---

## 7.2. macOS mapping

Portable API:

```text
USER_INTERACTIVE -> QOS_CLASS_USER_INTERACTIVE
USER_INITIATED   -> QOS_CLASS_USER_INITIATED
DEFAULT          -> QOS_CLASS_DEFAULT
UTILITY          -> QOS_CLASS_UTILITY
BACKGROUND       -> QOS_CLASS_BACKGROUND
```

Relative priority в portable API версии 1.0 не выставлять наружу.

По умолчанию использовать:

```text
relative_priority = 0
```

В дальнейшем можно добавить advanced macOS API, если появится реальный use case.

---

## 7.3. Native constants

Использовать значения Darwin ABI, проверенные по системным headers:

```text
QOS_CLASS_USER_INTERACTIVE = 0x21
QOS_CLASS_USER_INITIATED   = 0x19
QOS_CLASS_DEFAULT          = 0x15
QOS_CLASS_UTILITY          = 0x11
QOS_CLASS_BACKGROUND       = 0x09
```

Значения должны находиться только в macOS implementation.

Не экспортировать их через portable API.

---

## 7.4. FFM linkage

Использовать:

```java
Linker.nativeLinker();
```

и system/default lookup.

Примерная структура:

```java
private static final Linker LINKER = Linker.nativeLinker();

private static final MethodHandle SET_QOS = ...;
```

Signature:

```java
FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT
);
```

MethodHandle создаётся ровно один раз.

Не делать symbol lookup в `set()`.

---

## 7.5. Error handling

Функция возвращает:

```text
0        success
non-zero errno-style error code
```

При ошибке бросать библиотечное exception либо `IllegalStateException`.

Предпочтительно ввести:

```java
public class ThreadQosException extends RuntimeException {
    ...
}
```

Сообщение должно содержать:

- OS;
- requested QoS;
- native function;
- error code.

Например:

```text
Failed to set thread QoS:
os=macOS,
qos=USER_INTERACTIVE,
nativeFunction=pthread_set_qos_class_self_np,
errorCode=1
```

---

## 7.6. Optional readback

Можно использовать:

```c
pthread_t pthread_self(void);

int pthread_get_qos_class_np(
    pthread_t thread,
    qos_class_t *qos_class,
    int *relative_priority
);
```

или `qos_class_self()` там, где этого достаточно.

Readback должен использовать FFM memory segments.

Не добавлять readback, если он заметно увеличивает сложность первой версии.

---

# 8. Windows implementation

Windows нельзя считать noop platform.

Использовать официальный Windows per-thread Quality of Service / Power Throttling API.

---

## 8.1. Основной Windows QoS API

Использовать:

```c
BOOL SetThreadInformation(
    HANDLE hThread,
    THREAD_INFORMATION_CLASS ThreadInformationClass,
    LPVOID ThreadInformation,
    DWORD ThreadInformationSize
);
```

Thread:

```c
GetCurrentThread()
```

Information class:

```text
ThreadPowerThrottling
```

Structure:

```c
typedef struct _THREAD_POWER_THROTTLING_STATE {
    ULONG Version;
    ULONG ControlMask;
    ULONG StateMask;
} THREAD_POWER_THROTTLING_STATE;
```

---

## 8.2. QoS states Windows

Windows официально поддерживает:

### HighQoS

```text
ControlMask = THREAD_POWER_THROTTLING_EXECUTION_SPEED
StateMask   = 0
```

### EcoQoS

```text
ControlMask = THREAD_POWER_THROTTLING_EXECUTION_SPEED
StateMask   = THREAD_POWER_THROTTLING_EXECUTION_SPEED
```

### System-managed

```text
ControlMask = 0
StateMask   = 0
```

EcoQoS может заставлять систему:

- уменьшать CPU frequency;
- предпочитать более энергоэффективные cores;
- снижать power usage.

HighQoS отключает execution-speed throttling.

---

# 9. Windows priority

Для получения пяти portable QoS levels одного Power Throttling недостаточно, поскольку он практически задаёт три состояния:

```text
High
System Managed
Eco
```

Поэтому Windows provider должен дополнительно использовать:

```c
BOOL SetThreadPriority(
    HANDLE hThread,
    int nPriority
);
```

---

## 9.1. Portable mapping Windows

Начальный mapping для `1.0.0`:

```text
USER_INTERACTIVE
    HighQoS
    THREAD_PRIORITY_HIGHEST

USER_INITIATED
    HighQoS
    THREAD_PRIORITY_ABOVE_NORMAL

DEFAULT
    system-managed QoS
    THREAD_PRIORITY_NORMAL

UTILITY
    system-managed QoS
    THREAD_PRIORITY_BELOW_NORMAL

BACKGROUND
    EcoQoS
    THREAD_PRIORITY_LOWEST
```

Не использовать автоматически:

```text
THREAD_PRIORITY_TIME_CRITICAL
```

Не использовать автоматически:

```text
REALTIME_PRIORITY_CLASS
```

Это слишком сильные механизмы для portable QoS abstraction.

---

## 9.2. Не использовать background mode в v1

В первой версии не использовать автоматически:

```text
THREAD_MODE_BACKGROUND_BEGIN
THREAD_MODE_BACKGROUND_END
```

Причина: background processing mode влияет не только на CPU scheduling и создаёт stateful semantics, которые сложнее корректно восстановить.

Для `BACKGROUND` в v1 достаточно:

```text
EcoQoS + THREAD_PRIORITY_LOWEST
```

Если позже появится advanced Windows API, background mode можно добавить отдельно.

---

## 9.3. Windows native symbols

Нужны как минимум:

```text
GetCurrentThread
SetThreadInformation
SetThreadPriority
```

При необходимости:

```text
GetThreadInformation
GetThreadPriority
```

Symbols брать из Windows system libraries через FFM.

Не поставлять `Kernel32.dll`.

---

## 9.4. Windows error handling

Win32 functions возвращают `BOOL`.

При `FALSE` нужно получить `GetLastError`.

Предпочтительно использовать механизм FFM:

```java
Linker.Option.captureCallState("GetLastError")
```

если текущий JDK/ABI поддерживает этот captured state.

Не делать отдельный вызов `GetLastError()` после того, как Java/runtime уже мог выполнить другой native call и изменить error state.

Если `captureCallState("GetLastError")` недоступен на конкретном JDK/platform, найти корректный безопасный fallback.

---

# 10. Linux implementation

Для Linux основным portable QoS mechanism сделать **utilization clamping (`uclamp`)**.

Не использовать real-time scheduler policy автоматически.

---

# 11. Почему uclamp

Linux `uclamp` предназначен для передачи scheduler'у performance requirements задачи.

Доступны:

```text
UCLAMP_MIN
UCLAMP_MAX
```

Scheduler может использовать их для:

- task placement;
- CPU capacity selection;
- CPU frequency selection;
- schedutil DVFS decisions.

Это наиболее близкий Linux mechanism к performance intent macOS QoS / Windows QoS.

`uclamp` появился в Linux 5.3.

Kernel должен быть собран с поддержкой:

```text
CONFIG_UCLAMP_TASK
```

---

# 12. Linux API

Использовать:

```c
sched_setattr()
sched_getattr()
```

Но glibc не предоставляет wrapper для этих calls.

Поэтому использовать:

```c
syscall(SYS_sched_setattr, ...)
syscall(SYS_sched_getattr, ...)
```

через FFM.

---

# 13. Linux `sched_attr`

Поддержать kernel structure, включая поля:

```c
struct sched_attr {
    __u32 size;
    __u32 sched_policy;
    __u64 sched_flags;
    __s32 sched_nice;
    __u32 sched_priority;
    __u64 sched_runtime;
    __u64 sched_deadline;
    __u64 sched_period;
    __u32 sched_util_min;
    __u32 sched_util_max;
};
```

Нельзя полагаться на Java object layout.

Создать явный FFM `MemoryLayout` с корректными offsets/alignment.

Добавить tests на размер и offsets для поддерживаемых Linux architectures.

---

# 14. Linux syscall numbers

Поскольку `sched_setattr()`/`sched_getattr()` не имеют glibc wrapper, syscall number зависит от architecture.

Минимально поддержать:

```text
Linux x86_64
Linux aarch64
```

Текущие kernel syscall numbers:

```text
x86_64:
  sched_setattr = 314
  sched_getattr = 315

aarch64:
  sched_setattr = 274
  sched_getattr = 275
```

Не определять architecture через случайные string comparisons в hot path.

Architecture выбирается один раз при provider initialization.

Для неподдержанной Linux architecture:

```text
LinuxThreadQosProvider.isSupported() == false
```

и API работает как safe noop либо возвращает unsupported capability согласно общей политике.

Не угадывать syscall numbers.

В будущем можно добавить:

- x86 32-bit;
- ARM 32-bit;
- RISC-V;
- PowerPC;
- s390x;
- LoongArch.

---

# 15. Linux portable QoS mapping

Не менять автоматически:

- affinity;
- scheduler policy;
- RT policy;
- nice;
- cgroups.

Portable `ThreadQos.set()` в Linux должен менять только utilization clamp.

Это снижает риск разрушить существующую scheduling configuration пользователя.

---

## 15.1. Предлагаемые uclamp values

Linux uclamp использует scale:

```text
0 .. 1024
```

Для первой версии задать понятную library policy:

```text
USER_INTERACTIVE:
    util_min = 1024
    util_max = 1024

USER_INITIATED:
    util_min = 768
    util_max = 1024

DEFAULT:
    util_min = 0
    util_max = 1024

UTILITY:
    util_min = 0
    util_max = 768

BACKGROUND:
    util_min = 0
    util_max = 256
```

Важно:

`uclamp` НЕ резервирует процент CPU.

Значения — scheduler performance/capacity hints.

Например `768` не означает «этому потоку гарантировано 75% CPU time».

---

## 15.2. Mapping должен быть централизован

Не размазывать magic numbers.

Например:

```java
record LinuxUtilClamp(int min, int max) {
}
```

и один:

```java
switch (qosClass) { ... }
```

---

# 16. Сохранение existing Linux scheduling attributes

Перед `sched_setattr` выполнить:

```text
sched_getattr(currentThread)
```

Изменить только:

```text
sched_flags
sched_util_min
sched_util_max
```

и сохранить:

- existing `sched_policy`;
- existing `sched_nice`;
- existing RT priority;
- deadline fields;
- другие применимые attributes.

Не переводить чужой thread из `SCHED_FIFO` в `SCHED_OTHER`.

Не менять nice автоматически.

---

# 17. Linux flags

Использовать:

```text
SCHED_FLAG_UTIL_CLAMP_MIN
SCHED_FLAG_UTIL_CLAMP_MAX
```

при установке соответствующих полей.

Перед реализацией агент должен сверить constants с актуальными Linux UAPI headers.

Не копировать значения из случайного blog post.

---

# 18. Linux feature detection

Не считать любой Linux >= 5.3 автоматически поддерживающим uclamp.

Возможные ситуации:

- kernel старше 5.3;
- kernel собран без `CONFIG_UCLAMP_TASK`;
- security policy запрещает operation;
- syscall отсутствует;
- container/seccomp блокирует syscall.

Provider должен выполнить lazy capability probe.

Предпочтительно:

1. не делать native mutation при class initialization;
2. при первом `set()` либо explicit capability probe:
   - вызвать `sched_getattr`;
   - определить поддержку;
3. кэшировать capability result, если это безопасно.

Ошибки:

```text
ENOSYS       unsupported syscall
EOPNOTSUPP   uclamp not supported by kernel config
EPERM        operation denied
EINVAL       invalid layout/flags/value or old kernel behavior
```

различать по смыслу, насколько это возможно.

---

# 19. Linux errno

Для `syscall()` использовать:

```java
Linker.Option.captureCallState("errno")
```

для надёжного получения `errno` сразу после native call.

Не делать отдельный `errno` lookup после произвольных Java/FFM operations.

---

# 20. Unsupported OS behavior

Для:

- FreeBSD;
- Solaris;
- AIX;
- Android;
- неизвестных платформ;

в версии 1.0 использовать:

```text
NoOpThreadQosProvider
```

Поведение:

```java
ThreadQos.isSupported() == false
```

```java
ThreadQos.set(...) // no-op
```

```java
ThreadQos.wrap(..., runnable)
```

может вернуть исходный `Runnable`, если это не нарушает validation contract.

Не писать warning в лог на каждый вызов.

---

# 21. Failure policy

Нужно различать:

## Unsupported

Платформа или kernel feature не поддерживаются.

Portable API:

```text
no-op
```

и:

```java
isSupported() == false
```

## Supported, но operation failed

Например:

- Windows API вернул error;
- macOS pthread QoS вернул error;
- Linux uclamp был доступен, но текущий operation запрещён.

В этом случае **не скрывать проблему**.

Бросать:

```java
ThreadQosException
```

---

# 22. `ThreadQosException`

Добавить:

```java
public final class ThreadQosException extends RuntimeException {

    private final OperatingSystem operatingSystem;
    private final String nativeOperation;
    private final Integer nativeErrorCode;
}
```

API можно упростить.

Главное — exception должен позволять понять:

```text
что пытались сделать;
на какой ОС;
какая native function;
какой error code.
```

---

# 23. `wrap()`

Реализовать:

```java
public static Runnable wrap(
        QosClass qosClass,
        Runnable task
);
```

Пример:

```java
return () -> {
    ThreadQos.set(qosClass);
    task.run();
};
```

Также:

```java
public static <V> Callable<V> wrap(
        QosClass qosClass,
        Callable<V> task
);
```

На полностью unsupported platform допустимо вернуть исходный object:

```java
return task;
```

чтобы не создавать лишнюю lambda allocation.

---

# 24. Не делать implicit restore в `wrap()` v1

В первой версии:

```java
wrap(qos, task)
```

ставит QoS один раз в начале lifetime task и не пытается restore после завершения.

Это соответствует intended use case:

```text
long-lived dedicated platform thread
```

Не использовать `wrap()` для общего pool, где один worker последовательно выполняет задачи разных QoS без reset.

Документация должна это явно сказать.

---

# 25. Future scoped API

В roadmap можно добавить:

```java
try (var ignored = ThreadQos.scope(QosClass.USER_INTERACTIVE)) {
    ...
}
```

с сохранением и восстановлением native state.

Но не включать это в v1, пока корректный round-trip не доказан на всех трёх ОС.

---

# 26. Thread pools

Документация должна показать безопасный pattern для dedicated worker:

```java
ThreadFactory factory = task ->
        Thread.ofPlatform()
              .name("execution-", 0)
              .unstarted(ThreadQos.wrap(
                      QosClass.USER_INTERACTIVE,
                      task
              ));
```

Для generic shared executor предупредить:

```text
QoS является свойством native worker thread,
а не отдельной submitted task.
```

Поэтому смена QoS внутри task может повлиять на следующую task в том же worker thread.

---

# 27. Performance requirements

Проект ориентирован на low-latency users.

После initialization вызов `set()` должен:

- не выполнять OS detection;
- не выполнять native symbol lookup;
- не использовать reflection;
- не брать Java monitor lock;
- не логировать successful call;
- не создавать ненужные Java objects;
- не парсить strings;
- использовать precomputed MethodHandles/layouts/constants.

Нативный вызов сам по себе выполняется редко:

```text
обычно один раз при старте thread
```

Поэтому не надо делать опасные micro-optimizations только ради нескольких наносекунд.

---

# 28. FFM lifecycle

Все:

- `Linker`;
- `SymbolLookup`;
- `MethodHandle`;
- immutable layouts;

должны быть инициализированы один раз и жить столько же, сколько provider.

Для lookup, которому нужен `Arena`, использовать подходящий long-lived arena:

```java
Arena.global()
```

если это корректно по API и lifecycle.

Не создавать `Arena.ofConfined()` на каждый `set()` кроме случаев, где требуется небольшой temporary native struct.

Для per-call structs допускается confined arena, но оценить allocation cost.

Если структура фиксированного размера и вызов редкий, correctness важнее micro-optimization.

---

# 29. Native access requirement

Java FFM restricted operations требуют native access.

Для classpath application:

```text
--enable-native-access=ALL-UNNAMED
```

Для named module предпочтительно:

```text
--enable-native-access=<library-module-name>
```

Библиотека должна быть modular JAR, чтобы пользователь при module-path deployment мог разрешить native access только этой библиотеке.

---

# 30. JPMS module

Добавить `module-info.java`.

Рабочее module name:

```text
io.github.<owner>.threadqos
```

или другое стабильное имя после выбора groupId.

Экспортировать только public package.

Например:

```java
module io.github.owner.threadqos {
    exports io.github.owner.threadqos;
}
```

Internal packages не экспортировать.

---

# 31. Native access diagnostics

Если FFM call запрещён из-за native-access settings, exception должен содержать понятную подсказку.

Например:

```text
Native access is required for java-thread-qos.
Run the JVM with:
--enable-native-access=io.github.owner.threadqos

For classpath usage:
--enable-native-access=ALL-UNNAMED
```

Не проглатывать `IllegalCallerException`.

---

# 32. Java version

Baseline:

```text
Java 25
```

Причины:

- stable FFM API;
- актуальная LTS/современная runtime target проекта;
- нет необходимости поддерживать старые preview FFM variants.

Не пытаться поддерживать Java 17/21 через reflection/JNI compatibility layer.

Если позже будет спрос — рассматривать отдельную major version/module.

---

# 33. Build system

Использовать Maven.

Минимум:

```text
maven-compiler-plugin
maven-surefire-plugin
maven-source-plugin
maven-javadoc-plugin
maven-gpg-plugin
central-publishing-maven-plugin
```

Дополнительно по необходимости:

```text
maven-enforcer-plugin
maven-release-plugin
flatten-maven-plugin
```

Не добавлять plugins без реальной пользы.

---

# 34. Maven coordinates

Рекомендуемая схема:

```xml
<groupId>io.github.<github-user></groupId>
<artifactId>thread-qos</artifactId>
<version>0.1.0-SNAPSHOT</version>
```

Для Maven Central Sonatype часто автоматически предоставляет namespace вида:

```text
io.github.<github-user>
```

при регистрации через GitHub account.

Если есть собственный домен и хочется более стабильного namespace, можно использовать reverse-DNS собственного домена после DNS verification.

---

# 35. Project metadata

POM должен содержать всё, что требует Maven Central:

- `groupId`;
- `artifactId`;
- `version`;
- `name`;
- `description`;
- project `url`;
- license;
- developer information;
- SCM information.

Пример description:

```text
Cross-platform Java thread Quality of Service controls using the Foreign Function & Memory API.
```

---

# 36. License

Рекомендуется:

```text
Apache License 2.0
```

Причины:

- стандартная permissive OSS license;
- удобна для commercial и open-source consumers;
- хорошо подходит библиотеке;
- содержит explicit patent grant.

Добавить:

```text
LICENSE
NOTICE   // если требуется
```

---

# 37. Maven Central artifacts

Каждый release должен публиковать:

```text
thread-qos-X.Y.Z.jar
thread-qos-X.Y.Z-sources.jar
thread-qos-X.Y.Z-javadoc.jar
thread-qos-X.Y.Z.pom
```

Все требуемые artifacts должны иметь PGP/GPG signatures.

Central также требует metadata/checksums согласно текущим правилам.

Использовать официальный Central Publisher Portal flow, а не legacy OSSRH flow, если документация Sonatype не требует обратного.

---

# 38. Maven Central plugin

Использовать:

```text
org.sonatype.central:central-publishing-maven-plugin
```

Версию брать актуальную stable на момент реализации.

На момент подготовки ТЗ официальная документация показывает `0.9.0`, но agent должен проверить текущую stable version перед фиксацией POM.

Не копировать старые OSSRH deployment snippets из устаревших blog posts.

---

# 39. Maven Central credentials

Credentials не хранить в repository.

Использовать GitHub Actions secrets.

Минимум:

```text
CENTRAL_USERNAME
CENTRAL_PASSWORD
```

или эквивалентные Portal user-token values согласно актуальной документации.

PGP:

```text
GPG_PRIVATE_KEY
GPG_PASSPHRASE
```

Конкретные secret names могут отличаться.

---

# 40. Maven Central namespace setup

README для maintainer должен содержать setup steps:

1. Зарегистрироваться в Sonatype Central Portal.
2. Получить/подтвердить namespace.
3. Для `io.github.<username>` использовать GitHub identity verification, если Portal автоматически предоставляет namespace.
4. Для собственного reverse-domain groupId выполнить DNS verification.
5. Создать Central Portal user token.
6. Настроить GPG signing key.
7. Добавить secrets в GitHub Actions.
8. Выпустить первый test release.

---

# 41. Maven Central policy note

На дату подготовки ТЗ:

- Maven Central остаётся бесплатным для обычной community open-source публикации;
- Sonatype объявил publishing limits/tier changes с 1 октября 2026;
- artifacts коммерческого характера могут требовать Publisher Pro независимо от объёма.

Проект предполагается open-source community library.

Перед первым production release maintainer должен проверить актуальные Central Publisher Terms/Tiers.

---

# 42. GitHub Packages

GitHub Packages сделать **опциональным**, не основным repository.

Причина: GitHub Maven registry требует authentication token для package access, включая public packages согласно текущей GitHub документации.

Это ухудшает UX по сравнению с Maven Central:

Maven Central:

```xml
<dependency>
    ...
</dependency>
```

без дополнительных repository credentials.

GitHub Packages:

```text
repository config + GitHub authentication
```

Поэтому:

```text
Maven Central = stable public releases
GitHub Packages = optional snapshots/internal convenience
```

---

# 43. GitHub Releases

На каждый stable release создавать GitHub Release.

Tag:

```text
v1.2.3
```

Release должен содержать:

- changelog;
- Maven coordinates;
- минимальную Java version;
- notice о `--enable-native-access`;
- supported OS/architectures;
- ссылку на Maven Central artifact.

Опционально attach:

```text
thread-qos-X.Y.Z.jar
thread-qos-X.Y.Z-sources.jar
thread-qos-X.Y.Z-javadoc.jar
SHA256SUMS
```

Но GitHub Release assets не должны быть основным способом dependency consumption.

---

# 44. Versioning

Использовать Semantic Versioning.

До стабилизации API:

```text
0.1.0
0.2.0
...
```

Первый stable API:

```text
1.0.0
```

Rules:

```text
PATCH:
bug fixes, no API changes

MINOR:
backward-compatible features

MAJOR:
breaking API/behavior changes
```

Изменение portable QoS mapping, которое существенно меняет scheduling behavior, считать потенциально breaking behavioral change и явно документировать.

---

# 45. Snapshots

Рекомендуемая схема:

```text
main:
0.x.y-SNAPSHOT

release tags:
0.x.y / 1.x.y
```

Не обязательно публиковать каждый snapshot наружу.

Для собственной разработки достаточно:

```bash
mvn install
```

Для distributed snapshot testing можно использовать GitHub Packages либо Sonatype snapshot infrastructure, если это оправдано.

Stable consumers должны использовать Maven Central release versions.

---

# 46. CI

GitHub Actions.

На каждый push/PR:

```text
compile
unit tests
integration tests where available
javadoc
package
```

Matrix минимум:

```text
Linux x86_64
macOS arm64
Windows x86_64
```

Желательно:

```text
Linux arm64
macOS x86_64, если доступен runner
Windows arm64, если доступен runner
```

Linux arm64 особенно важен, потому что syscall numbers отличаются.

---

# 47. CI native access

Tests должны запускаться с:

```text
--enable-native-access=<module-name>
```

или:

```text
--enable-native-access=ALL-UNNAMED
```

в зависимости от test setup.

Добавить отдельный negative test/process test, подтверждающий понятную ошибку без native access, если это стабильно тестируется на JDK 25.

---

# 48. Unit tests

Покрыть:

- provider selection;
- enum mapping;
- unsupported platform behavior;
- null checks;
- virtual-thread rejection;
- wrapper behavior;
- capability reporting;
- exception formatting;
- architecture detection;
- Linux uclamp mapping;
- Windows priority mapping;
- macOS constants mapping.

---

# 49. Integration tests macOS

На реальном macOS runner:

```java
Thread.ofPlatform().start(() -> {
    ThreadQos.set(QosClass.USER_INTERACTIVE);
});
```

Проверить отсутствие native error.

Повторить для всех QoS classes.

Если реализован readback — проверить class round-trip.

Не проверять:

```text
"thread физически оказался на P-core #N"
```

Это не контракт API.

---

# 50. Integration tests Windows

На Windows runner:

Проверить успешные вызовы:

```text
USER_INTERACTIVE
USER_INITIATED
DEFAULT
UTILITY
BACKGROUND
```

При наличии readback:

- проверить `GetThreadInformation`;
- проверить `GetThreadPriority`.

Не строить test на конкретном physical core placement.

---

# 51. Integration tests Linux

На Linux:

1. `sched_getattr(0, ...)`.
2. Сохранить исходный attr.
3. Применить каждый portable QoS.
4. Прочитать attr обратно.
5. Проверить `sched_util_min/max`.
6. В конце вернуть DEFAULT/original clamps.

Если runner kernel не поддерживает uclamp:

- test не должен ложно падать;
- capability test должен подтвердить unsupported;
- это должно быть явно видно в CI output.

Отдельно желательно иметь один environment/kernel, где uclamp гарантированно поддерживается, чтобы Linux implementation действительно тестировалась, а не всегда skip.

---

# 52. FFM layout tests

Особенно тщательно тестировать:

```text
THREAD_POWER_THROTTLING_STATE
sched_attr
```

Проверить:

- size;
- alignment;
- offsets;
- integer widths;
- signed/unsigned assumptions.

Нельзя рассчитывать на то, что Java primitive layout автоматически соответствует C struct с padding.

---

# 53. Native symbol resolution tests

Отдельные tests должны проверить, что на соответствующей ОС находятся необходимые symbols.

macOS:

```text
pthread_set_qos_class_self_np
```

Windows:

```text
GetCurrentThread
SetThreadInformation
SetThreadPriority
```

Linux:

```text
syscall
```

---

# 54. Benchmarking

Добавить JMH module или profile, но не делать JMH runtime dependency основной библиотеки.

Benchmarks:

```text
provider lookup/access
ThreadQos.isSupported()
native set QoS
wrapper startup overhead
```

Главный показатель:

```text
нет Java overhead в обычном application hot path,
потому что QoS задаётся один раз при старте worker.
```

Не рекламировать meaningless nanosecond claims.

---

# 55. Example module

Добавить маленький example:

```text
examples/
  basic/
```

или в README:

```java
public final class Example {

    public static void main(String[] args) {

        Thread execution = Thread.ofPlatform()
                .name("execution")
                .start(ThreadQos.wrap(
                        QosClass.USER_INTERACTIVE,
                        Example::executionLoop
                ));

        Thread writer = Thread.ofPlatform()
                .name("writer")
                .start(ThreadQos.wrap(
                        QosClass.UTILITY,
                        Example::writerLoop
                ));
    }
}
```

---

# 56. README

README должен отвечать на вопросы:

1. Что делает библиотека?
2. Чем QoS отличается от thread priority?
3. Чем QoS отличается от CPU affinity?
4. Какие ОС поддерживаются?
5. Какие architecture поддерживаются?
6. Как подключить Maven dependency?
7. Как включить native access?
8. Как использовать с `Thread.ofPlatform()`?
9. Почему нельзя использовать virtual threads?
10. Что именно делает каждый QoS level на каждой ОС?
11. Какие гарантии библиотека НЕ даёт?
12. Как проверить поддержку runtime?

---

# 57. README compatibility table

Обязательно таблица:

| Platform | Mechanism | Status |
|---|---|---|
| macOS | `pthread_set_qos_class_self_np` | Supported |
| Windows | `SetThreadInformation` + `SetThreadPriority` | Supported |
| Linux x86_64 | `sched_setattr` + `uclamp` | Supported if kernel supports uclamp |
| Linux aarch64 | `sched_setattr` + `uclamp` | Supported if kernel supports uclamp |
| Other | No-op | Unsupported |

---

# 58. README semantic table

Добавить:

| QosClass | macOS | Windows | Linux |
|---|---|---|---|
| USER_INTERACTIVE | User Interactive | HighQoS + Highest | uclamp 1024..1024 |
| USER_INITIATED | User Initiated | HighQoS + Above Normal | uclamp 768..1024 |
| DEFAULT | Default | System + Normal | uclamp 0..1024 |
| UTILITY | Utility | System + Below Normal | uclamp 0..768 |
| BACKGROUND | Background | EcoQoS + Lowest | uclamp 0..256 |

Явно указать:

```text
Это semantic mapping библиотеки, а не утверждение, что API разных ОС эквивалентны 1:1.
```

---

# 59. Security / safety documentation

Документировать, что библиотека:

- вызывает native OS APIs;
- может менять scheduler behavior;
- требует `--enable-native-access`;
- не должна использоваться из virtual threads;
- не даёт hard realtime guarantee;
- не изолирует CPU;
- не блокирует interrupts;
- не управляет cgroups;
- не управляет process priority;
- не меняет affinity.

---

# 60. Out of scope v1

Не реализовывать в `1.0`:

- CPU affinity;
- CPU isolation;
- NUMA placement;
- interrupt affinity;
- `isolcpus`;
- `nohz_full`;
- realtime `SCHED_FIFO`;
- realtime `SCHED_RR`;
- `SCHED_DEADLINE`;
- Windows CPU Sets;
- Windows realtime priority class;
- macOS Mach thread affinity;
- process-wide QoS;
- automatic tuning JVM GC/compiler threads;
- virtual-thread QoS;
- Java agent;
- JVMTI;
- native binaries.

---

# 61. Future project direction

Архитектуру сделать так, чтобы позже проект можно было расширить соседними APIs.

Например:

```text
ThreadQos
ThreadAffinity
ThreadScheduling
ThreadRealtime
ThreadPlacement
```

Но не добавлять пустые abstractions заранее.

---

# 62. Potential v2: Windows CPU Sets

Отдельным API можно добавить:

```java
ThreadPlacement.preferPerformanceCores();
ThreadPlacement.preferEfficiencyCores();
```

через:

```text
GetSystemCpuSetInformation
SetThreadSelectedCpuSets
```

Это уже placement/soft affinity, поэтому не должно находиться внутри `ThreadQos`.

---

# 63. Potential v2: Linux scheduler

Отдельный explicit expert API:

```java
LinuxThreadScheduling.setBatch();
LinuxThreadScheduling.setIdle();
LinuxThreadScheduling.setFifo(priority);
LinuxThreadScheduling.setRoundRobin(priority);
```

Не маппить portable QoS автоматически на `SCHED_FIFO`.

---

# 64. Potential v2: CPU affinity

Если проект разрастётся, можно реализовать:

Linux:

```text
sched_setaffinity
```

Windows:

```text
CPU Sets / affinity APIs
```

macOS:

```text
только доступные scheduler hints; не обещать hard affinity
```

Это отдельная функция.

---

# 65. Logging

Core library не должна иметь dependency на:

- SLF4J;
- Log4j;
- java.util.logging adapters.

По умолчанию library молчит.

Ошибки возвращаются exception/capabilities.

При необходимости diagnostics можно получить через public methods.

---

# 66. Dependencies

Цель:

```text
zero runtime dependencies
```

Только JDK 25.

Test dependencies допустимы:

- JUnit 5;
- AssertJ при необходимости;
- JMH в benchmark module/profile.

Если AssertJ не нужен — не добавлять.

---

# 67. Binary/API compatibility

После `1.0.0`:

- не ломать public API в minor releases;
- internal packages не считать API;
- не экспортировать native implementation classes через JPMS;
- не обещать стабильность internal constants.

Можно добавить Revapi/japicmp позже, если проект станет публично используемым.

---

# 68. Reproducible build

Желательно включить reproducible Maven build:

```text
project.build.outputTimestamp
```

Release pipeline должен давать воспроизводимые JAR metadata, насколько это практически возможно.

---

# 69. Supply-chain hygiene

GitHub Actions:

- permissions минимальные;
- release job отделён от PR build;
- publishing secrets доступны только release job;
- forks не получают publishing secrets;
- dependencies/actions закреплять на проверенные major/version или commit policy проекта;
- Dependabot/Renovate допустим.

---

# 70. Release workflow

Предпочтительный flow:

```text
1. PR merge в main
2. CI green
3. подготовить version/changelog
4. tag vX.Y.Z
5. GitHub Actions:
   - checkout
   - JDK 25
   - mvn verify
   - build sources
   - build javadoc
   - sign
   - publish Maven Central
   - create/update GitHub Release
6. verify artifact in Maven Central
```

Не публиковать Central artifact из обычного PR workflow.

---

# 71. Release immutability

Maven Central release versions immutable.

Нельзя:

```text
перезалить 1.0.0 с исправленным кодом
```

Нужно:

```text
1.0.1
```

Release pipeline должен предотвращать случайную повторную публикацию существующей version.

---

# 72. CHANGELOG

Добавить:

```text
CHANGELOG.md
```

Формат можно взять Keep a Changelog или простой project-specific.

Каждый release:

```text
Added
Changed
Fixed
Platform changes
```

Platform behavior особенно важно фиксировать отдельно.

---

# 73. Contributing

Добавить:

```text
CONTRIBUTING.md
```

Минимум:

```text
JDK 25
mvn verify
platform integration tests
style rules
release только maintainers
```

---

# 74. Code style

Не подключать тяжёлый formatter только ради formatter.

Использовать нормальный Java style.

Основные требования:

- final where useful, но без фанатизма;
- native constants named;
- no magic offsets;
- no wildcard imports;
- public API documented;
- native ABI comments с ссылкой на authoritative header/docs.

---

# 75. Source comments for native ABI

Возле platform constants оставлять source reference.

Например:

```java
// Darwin pthread/qos.h
private static final int QOS_CLASS_USER_INTERACTIVE = 0x21;
```

Linux:

```java
// Linux UAPI: include/uapi/linux/sched.h
```

Windows:

```java
// Windows SDK: processthreadsapi.h
```

Не вставлять большие куски чужих headers.

---

# 76. Tests against accidental ABI drift

Если возможно, добавить small tests, которые проверяют official constants через runtime behavior/readback.

Но не вводить C compilation исключительно для validation.

Проект должен оставаться pure Java build.

---

# 77. Error-message quality

Не выдавать пользователю:

```text
WrongMethodTypeException
```

или:

```text
NoSuchElementException
```

как итоговую ошибку platform setup.

Все ожидаемые native linkage/config errors должны по возможности оборачиваться в понятный `ThreadQosException`.

Сохранять cause.

---

# 78. Lazy initialization

Platform native linkage предпочтительно делать lazy.

Причина:

Если JAR просто лежит в classpath, но QoS API не используется, он не должен:

- требовать native access;
- падать;
- выполнять syscall;
- загружать platform-specific symbols.

Например:

```text
ThreadQos class load
    -> provider selected

first ThreadQos.set()
    -> platform native bindings initialized
```

Если реализация получается значительно проще с eager MethodHandles, допустимо eager-init только внутри provider, который создаётся исключительно для текущей ОС.

---

# 79. No cross-platform symbol loading

На Linux никогда не пытаться resolve:

```text
SetThreadInformation
pthread_set_qos_class_self_np
```

На Windows — Linux/macOS symbols.

Это должно следовать из provider architecture.

---

# 80. API null handling

```java
ThreadQos.set(null)
```

должен бросать:

```text
NullPointerException
```

через:

```java
Objects.requireNonNull(...)
```

до native code.

То же для `wrap`.

---

# 81. Thread interruption

QoS API не должен:

- очищать interruption status;
- ставить interruption status;
- ловить/скрывать interrupts.

QoS operation не связан с Java interruption.

---

# 82. Thread priority interaction

Документировать platform differences.

macOS:

```text
QoS является отдельным scheduler concept.
```

Windows:

```text
portable mapping библиотеки включает SetThreadPriority.
```

Linux:

```text
v1 portable QoS не меняет nice/scheduling policy.
```

Из-за этого portable classes эквивалентны по intent, а не по точной scheduler semantics.

---

# 83. Interaction with Java `Thread.setPriority`

Не вызывать `Thread.setPriority()`.

Причины:

- semantics JVM/platform dependent;
- Windows implementation должна явно контролировать Win32 native priority;
- Linux QoS строится на uclamp;
- macOS — на pthread QoS.

README должен предупредить, что пользовательское изменение Java/native priority после `ThreadQos.set()` может изменить итоговое поведение scheduler.

---

# 84. Interaction with Chronicle Thread Affinity

Проект не должен зависеть от Chronicle Thread Affinity.

Но библиотеки должны быть совместимы концептуально:

```java
try (AffinityLock ignored = AffinityLock.acquireLock(cpu)) {
    ThreadQos.set(QosClass.USER_INTERACTIVE);
    runLoop();
}
```

На Linux affinity и QoS могут использоваться одновременно.

На macOS Chronicle affinity не следует считать hard CPU pinning.

Никакой compile/runtime dependency на Chronicle.

---

# 85. Acceptance criteria — code

Релиз-кандидат считается готовым, если:

1. Java 25 Maven project собирается с нуля.
2. Runtime dependencies = 0.
3. JNI/JNA/JNR отсутствуют.
4. Native binaries в repository отсутствуют.
5. macOS implementation реально вызывает pthread QoS.
6. Windows implementation реально вызывает Win32 QoS + priority.
7. Linux x86_64 implementation реально использует uclamp.
8. Linux aarch64 implementation реально использует uclamp.
9. Unsupported platforms безопасно работают через noop.
10. Virtual threads запрещены.
11. Native symbols/method handles кэшируются.
12. OS/architecture detection не происходит на каждый call.
13. Ошибки native APIs не проглатываются.
14. Tests проходят на поддерживаемых CI platforms.
15. Javadocs собираются без ошибок.
16. Sources JAR создаётся.
17. Javadoc JAR создаётся.
18. GPG signing profile работает.
19. Central publication profile работает.
20. GitHub Release workflow работает.

---

# 86. Acceptance criteria — UX

Пользователь после релиза должен иметь возможность написать:

```xml
<dependency>
    <groupId>io.github.someone</groupId>
    <artifactId>thread-qos</artifactId>
    <version>1.0.0</version>
</dependency>
```

запустить JVM с native access:

```text
--enable-native-access=io.github.someone.threadqos
```

и использовать:

```java
Thread.ofPlatform()
        .start(ThreadQos.wrap(
                QosClass.USER_INTERACTIVE,
                this::run
        ));
```

без:

- native library installation;
- JNI headers;
- compiler toolchain;
- CMake;
- platform-specific artifacts;
- custom Maven repository.

---

# 87. Предлагаемый implementation plan

## Phase 0 — repository/bootstrap

Создать:

```text
pom.xml
README.md
LICENSE
CHANGELOG.md
.gitignore
module-info.java
basic package structure
GitHub Actions CI
```

Пока без native implementation.

---

## Phase 1 — public API + noop

Реализовать:

```text
QosClass
ThreadQos
QosCapabilities
OperatingSystem
ThreadQosException
NoOpThreadQosProvider
ProviderFactory
```

Добавить unit tests.

---

## Phase 2 — macOS

Реализовать:

```text
pthread_set_qos_class_self_np
```

через FFM.

Integration tests на macOS.

---

## Phase 3 — Windows

Реализовать:

```text
GetCurrentThread
SetThreadInformation
SetThreadPriority
GetLastError capture
```

Integration tests Windows.

---

## Phase 4 — Linux x86_64

Реализовать:

```text
syscall(SYS_sched_getattr)
syscall(SYS_sched_setattr)
uclamp
errno capture
```

Preserve existing scheduling attributes.

Integration tests.

---

## Phase 5 — Linux aarch64

Добавить architecture-specific syscall numbers.

Прогнать реальные arm64 integration tests.

---

## Phase 6 — documentation/examples

README:

- usage;
- OS mapping;
- native-access;
- virtual threads;
- limitations;
- examples.

---

## Phase 7 — release engineering

Настроить:

- sources;
- javadocs;
- GPG;
- Central Publisher Portal;
- GitHub release workflow;
- changelog/versioning.

Выпустить:

```text
0.1.0
```

как первый публичный preview.

---

## Phase 8 — validation before 1.0

Проверить API на реальном проекте.

Минимум:

```text
macOS Apple Silicon
Windows x64
Linux x86_64
Linux arm64
```

Проверить с long-lived threads:

```text
market-data style busy loop
execution style event loop
utility writer
background maintenance
```

После feedback стабилизировать:

```text
1.0.0
```

---

# 88. Definition of done для агента

После выполнения агент обязан предоставить:

1. Список созданных/изменённых файлов.
2. Итоговую package/module structure.
3. Public API.
4. Таблицу platform mappings.
5. Описание FFM bindings.
6. Какие native symbols используются.
7. Какие syscalls используются на Linux.
8. Результаты `mvn verify`.
9. Результаты platform integration tests.
10. Какие тесты были skipped и почему.
11. Команду запуска с `--enable-native-access`.
12. Maven Central publishing setup status.
13. Что осталось сделать вручную владельцу:
    - Sonatype account/namespace;
    - GPG key;
    - GitHub secrets;
    - первый release.
14. Не оставлять TODO вместо работающей реализации, если TODO не относится к явно указанному future scope.

---

# 89. Важное требование агенту

Перед написанием native binding:

1. Сверять ABI только с authoritative sources:
   - Apple headers/docs;
   - Microsoft Learn / Windows SDK;
   - Linux UAPI/kernel docs;
   - Oracle JDK FFM docs.
2. Не доверять случайным StackOverflow/blog constants.
3. Проверить:
   - C integer width;
   - pointer width;
   - struct alignment;
   - padding;
   - calling convention;
   - return type;
   - error semantics.
4. Для каждого platform binding написать integration test до объявления phase завершённой.

---

# 90. Recommended repository description

```text
Cross-platform Java thread QoS and performance hints for macOS, Windows and Linux using the Foreign Function & Memory API.
```

---

# 91. Recommended first release scope

Первый реальный usable release:

```text
0.1.0
```

должен уже включать:

```text
macOS QoS
Windows QoS
Linux x86_64 uclamp
Linux arm64 uclamp
portable API
Maven Central publication
CI
documentation
```

Не выпускать пустой `0.1.0`, где работает только macOS.

---

# 92. Рекомендация по repository/publication

Основная схема:

```text
GitHub public repository
        |
        +-- GitHub Actions CI
        |
        +-- GitHub Releases
        |
        +-- Maven Central
```

Consumers:

```text
Maven / Gradle
      |
      +--> Maven Central
```

Не заставлять consumers использовать GitHub Packages.

---

# 93. Возможная структура будущего проекта

Если scope расширится за пределы QoS:

```text
java-thread-control
  |
  +-- thread-qos
  +-- thread-affinity
  +-- thread-scheduling
```

Но **не делать multi-module repository сейчас**, пока есть только QoS.

Начать с:

```text
java-thread-qos
```

single-module, single-JAR.

При появлении второго реально используемого feature провести отдельный architecture decision.

---

# 94. References / authoritative sources

## Java FFM

Oracle Java 25 Core Libraries / native access:

https://docs.oracle.com/en/java/javase/25/core/java-core-libraries-developer-guide.pdf

Java 25 restricted FFM methods:

https://docs.oracle.com/en/java/javase/25/docs/api/restricted-list.html

Java 25 errno/call-state capture:

https://docs.oracle.com/en/java/javase/25/core/checking-native-errors-using-errno.html

---

## macOS

Apple open-source pthread QoS header:

https://github.com/apple-oss-distributions/libpthread/blob/main/include/pthread/qos.h

Apple libpthread QoS implementation:

https://github.com/apple-oss-distributions/libpthread/blob/main/src/qos.c

---

## Windows

Microsoft `SetThreadInformation`:

https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-setthreadinformation

Microsoft Quality of Service:

https://learn.microsoft.com/en-us/windows/win32/procthread/quality-of-service

Microsoft `THREAD_POWER_THROTTLING_STATE`:

https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/ns-processthreadsapi-thread_power_throttling_state

Microsoft `SetThreadPriority`:

https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-setthreadpriority

---

## Linux

Linux kernel utilization clamping:

https://kernel.org/doc/html/latest/scheduler/sched-util-clamp.html

Linux `sched_setattr(2)`:

https://man7.org/linux/man-pages/man2/sched_setattr.2.html

Linux scheduler overview:

https://man7.org/linux/man-pages/man7/sched.7.html

Linux x86_64 syscall table:

https://github.com/torvalds/linux/blob/master/arch/x86/entry/syscalls/syscall_64.tbl

Linux asm-generic syscall table:

https://github.com/torvalds/linux/blob/master/include/uapi/asm-generic/unistd.h

---

## Maven Central

Central publishing requirements:

https://central.sonatype.org/publish/requirements/

Central Maven publishing:

https://central.sonatype.org/publish/publish-portal-maven/

Namespace registration:

https://central.sonatype.org/register/namespace/

Central Portal registration:

https://central.sonatype.org/register/central-portal/

---

## GitHub Packages / releases

Publishing Java packages with Maven:

https://docs.github.com/en/actions/tutorials/publish-packages/publish-java-packages-with-maven

GitHub Maven registry:

https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry
