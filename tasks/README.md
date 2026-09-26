# Java Thread QoS — тикеты по этапам

Источник требований: [`../java-thread-qos-spec.md`](../java-thread-qos-spec.md). Ссылки `§N` указывают на разделы спецификации. Один тикет = один файл.

Порядок этапов линейный, кроме этапов 3, 4, 5 (macOS, Windows, Linux x86_64): они независимы и могут идти параллельно после этапа 2. Этап 0 не содержит кода: это решения, без которых код пришлось бы переписывать.

Пометка **[правка]** означает, что тикет отклоняется от текста спецификации по итогам ревью; сводка отклонений внизу.

## Статус (2026-09-26)

| Этап | Статус | Примечание |
|---|---|---|
| 0. Решения | done | все девять решений внесены в спецификацию (§4, §15.1.1, §16.1, §23.1, §29, §31, §32.1, §47, §95) |
| 1. Bootstrap | done | `mvn verify` зелёный локально; CI-прогон на GitHub runner’ах — после push |
| 2. Публичный API | done | 154 теста в `mvn verify` на macOS (16 skipped — integration-тесты других ОС) |
| 3. macOS | done | integration + round-trip через `qos_class_self()` проверены на macOS arm64 |
| 4. Windows | done (code) | unit-тесты на фейке; integration-тесты выполняются только на `windows-latest` |
| 5. Linux x86_64 | done (code) | в Docker (ядро без `CONFIG_UCLAMP_TASK`) проверены syscall-биндинг и переход EOPNOTSUPP → unsupported; полный uclamp-путь — на `ubuntu-24.04` |
| 6. Linux aarch64 | done (code) | то же в Docker arm64 |
| 7. Документация | done | README, Javadoc, `examples/basic`, `-Pbenchmarks`, ABI-тесты |
| 8. Release engineering | done, кроме T-8.6 | профиль `release`, Central plugin, `release.yml`, `RELEASING.md`, CHANGELOG; сам релиз требует аккаунта Central, GPG-ключа и secrets |
| 9. Валидация 1.0.0 | blocked | ждёт релиза 0.1.0 |

Статус каждого тикета — в поле **Статус** и разделе **Результат** внутри файла тикета.

## Этап 0. Решения и правки спецификации

Папка: `00-decisions/`

- [D-01 Выбрать groupId и имя модуля](00-decisions/D-01-groupid-module-name.md) — §30, §34, §86
- [D-02 Политика ошибок внутри wrap()](00-decisions/D-02-wrap-failure-policy.md) **[правка]** — §21, §23, §24
- [D-03 Поведение wrap() на unsupported-платформе](00-decisions/D-03-wrap-on-unsupported.md) **[правка]** — §2.4, §20, §23
- [D-04 Linux: потоки с RT/deadline policy](00-decisions/D-04-linux-rt-threads.md) **[правка]** — §16
- [D-05 Linux: остаются ли UTILITY/BACKGROUND только на uclamp](00-decisions/D-05-linux-lower-classes-policy.md) **[правка]** — §15, §15.1, §57, §58, §63
- [D-06 Расширить QosCapabilities](00-decisions/D-06-capabilities-shape.md) **[правка]** — §4, §65
- [D-07 Раздел «Минимальные версии ОС и рантаймов»](00-decisions/D-07-minimum-os-versions.md) **[правка]** — новый раздел рядом с §32, §60
- [D-08 Native access на JDK 25](00-decisions/D-08-native-access-jdk25.md) **[правка]** — §29, §31, §47
- [D-09 Идентификаторы и модальность требований](00-decisions/D-09-requirement-ids.md) — весь документ

## Этап 1. Bootstrap репозитория

Папка: `01-bootstrap/`

- [T-1.1 pom.xml](01-bootstrap/T-1.1-pom.md) — §2.1, §32, §33, §47, §66, §68
- [T-1.2 module-info.java и структура пакетов](01-bootstrap/T-1.2-module-info-packages.md) — §6, §30, §67
- [T-1.3 Файлы репозитория](01-bootstrap/T-1.3-repo-files.md) — §36, §56, §72, §73
- [T-1.4 GitHub Actions CI](01-bootstrap/T-1.4-ci-workflow.md) — §46, §69
- [T-1.5 Проверка native access в CI](01-bootstrap/T-1.5-ci-native-access.md) **[правка]** — §47 (после правки D-08)

## Этап 2. Публичный API и noop-провайдер

Папка: `02-public-api/`

- [T-2.1 Enum'ы QosClass, OperatingSystem, Architecture](02-public-api/T-2.1-enums.md) **[правка]** — §3.1, §4, §14
- [T-2.2 QosCapabilities](02-public-api/T-2.2-qos-capabilities.md) **[правка]** — §4
- [T-2.3 ThreadQosException](02-public-api/T-2.3-thread-qos-exception.md) — §7.5, §22, §77
- [T-2.4 ThreadQosProvider и ThreadQosProviderFactory](02-public-api/T-2.4-provider-factory.md) **[правка]** — §5, §27, §78, §79
- [T-2.5 Фасад ThreadQos](02-public-api/T-2.5-thread-qos-facade.md) **[правка]** — §2.3, §2.4, §3.2, §23, §24, §80, §81, §83
- [T-2.6 NoOpThreadQosProvider](02-public-api/T-2.6-noop-provider.md) — §20, §65
- [T-2.7 Диагностика native access](02-public-api/T-2.7-native-access-diagnostics.md) **[правка]** — §29, §31 (после правки D-08)
- [T-2.8 FfmSupport](02-public-api/T-2.8-ffm-support.md) **[правка]** — §19, §27, §28, §77
- [T-2.9 Unit-тесты этапа 2](02-public-api/T-2.9-unit-tests-phase2.md) — §48

## Этап 3. macOS

Папка: `03-macos/`

- [T-3.1 MacOsQosNative](03-macos/T-3.1-macos-native.md) — §7.1–§7.4, §75
- [T-3.2 MacOsThreadQosProvider](03-macos/T-3.2-macos-provider.md) — §7.2, §7.5, §21
- [T-3.3 Readback через qos_class_self()](03-macos/T-3.3-macos-readback.md) — §7.6
- [T-3.4 Integration tests macOS](03-macos/T-3.4-macos-integration-tests.md) — §49, §53
- [T-3.5 Документация macOS](03-macos/T-3.5-macos-docs.md) **[правка]** — §3.1, §59, §82

## Этап 4. Windows

Папка: `04-windows/`

- [T-4.1 WindowsQosNative](04-windows/T-4.1-windows-native.md) **[правка]** — §8.1, §9.3, §9.4, §75, §89
- [T-4.2 Layout THREAD_POWER_THROTTLING_STATE](04-windows/T-4.2-windows-throttling-layout.md) — §8.1, §52
- [T-4.3 Capability probe Windows](04-windows/T-4.3-windows-capability-probe.md) **[правка]** — §8, §18, §21, D-06, D-07
- [T-4.4 WindowsThreadQosProvider](04-windows/T-4.4-windows-provider.md) — §9.1, §9.2
- [T-4.5 Обработка ошибок Windows](04-windows/T-4.5-windows-error-handling.md) — §9.4, §21, §22
- [T-4.6 Документация Windows](04-windows/T-4.6-windows-docs.md) **[правка]** — §82, §83, D-07

## Этап 5. Linux x86_64

Папка: `05-linux-x86_64/`

- [T-5.1 LinuxSchedAttr layout](05-linux-x86_64/T-5.1-linux-sched-attr-layout.md) — §13, §52
- [T-5.2 Таблица syscall-номеров](05-linux-x86_64/T-5.2-linux-syscall-numbers.md) **[правка]** — §14
- [T-5.3 LinuxQosNative](05-linux-x86_64/T-5.3-linux-native.md) **[правка]** — §12, §17, §19, §89
- [T-5.4 Capability probe Linux](05-linux-x86_64/T-5.4-linux-capability-probe.md) **[правка]** — §18, §21, D-06
- [T-5.5 LinuxThreadQosProvider.set()](05-linux-x86_64/T-5.5-linux-provider-set.md) **[правка]** — §15, §15.1, §15.2, §16, §17
- [T-5.6 Integration tests Linux](05-linux-x86_64/T-5.6-linux-integration-tests.md) — §51, §53
- [T-5.7 Гарантированная среда с uclamp в CI](05-linux-x86_64/T-5.7-linux-uclamp-ci-guard.md) — §51, §85 п.7
- [T-5.8 Документация Linux](05-linux-x86_64/T-5.8-linux-docs.md) **[правка]** — §15.1, §57, §58, §59, D-05

## Этап 6. Linux aarch64

Папка: `06-linux-aarch64/`

- [T-6.1 Номера syscall для aarch64](06-linux-aarch64/T-6.1-aarch64-syscall-numbers.md) — §14
- [T-6.2 Прогон на ubuntu-24.04-arm](06-linux-aarch64/T-6.2-aarch64-ci-run.md) — §46, §51

## Этап 7. Документация, примеры, бенчмарки

Папка: `07-docs-examples/`

- [T-7.1 README](07-docs-examples/T-7.1-readme.md) — §24, §26, §56–§59, §82–§84
- [T-7.2 Javadoc публичного API](07-docs-examples/T-7.2-javadoc.md) — §74
- [T-7.3 Пример examples/basic](07-docs-examples/T-7.3-example-basic.md) — §55
- [T-7.4 JMH-профиль](07-docs-examples/T-7.4-jmh-profile.md) — §54, §66
- [T-7.5 Тесты защиты от ABI drift](07-docs-examples/T-7.5-abi-drift-tests.md) — §76

## Этап 8. Release engineering

Папка: `08-release/`

- [T-8.1 Sources, javadoc, GPG](08-release/T-8.1-sources-javadoc-gpg.md) — §33, §37
- [T-8.2 Central Publisher Portal](08-release/T-8.2-central-portal.md) — §35, §38, §39, §41
- [T-8.3 Release workflow](08-release/T-8.3-release-workflow.md) — §43, §69, §70, §71
- [T-8.4 Maintainer setup](08-release/T-8.4-maintainer-setup-docs.md) — §40, §41
- [T-8.5 Versioning и CHANGELOG](08-release/T-8.5-versioning-changelog.md) — §44, §45, §72
- [T-8.6 Релиз 0.1.0](08-release/T-8.6-release-0-1-0.md) — §91

## Этап 9. Валидация перед 1.0.0

Папка: `09-validation/`

- [T-9.1 Проверка на реальном приложении](09-validation/T-9.1-real-app-validation.md) — §87 Phase 8
- [T-9.2 Стабилизация API](09-validation/T-9.2-api-stabilization.md) — §3.2, §67
- [T-9.3 Релиз 1.0.0](09-validation/T-9.3-release-1-0-0.md) — §85, §86

## Сводка отклонений от спецификации

| Раздел | Было в спецификации | Изменение | Тикеты |
|---|---|---|---|
| §29, §31, §47 | Без флага native access ожидается `IllegalCallerException` | На JDK 25 по умолчанию только warning; проверять `Module.isNativeAccessEnabled()`, обрабатывать `deny` | [D-08](00-decisions/D-08-native-access-jdk25.md), [T-1.5](01-bootstrap/T-1.5-ci-native-access.md), [T-2.7](02-public-api/T-2.7-native-access-diagnostics.md) |
| §9.3 | «Symbols брать из Windows system libraries» | Явно `libraryLookup("kernel32")`, default lookup их не содержит | [T-4.1](04-windows/T-4.1-windows-native.md) |
| §12 | `syscall(SYS_sched_setattr, ...)` через FFM | Обязателен `firstVariadicArg(1)` | [T-5.3](05-linux-x86_64/T-5.3-linux-native.md) |
| §8, §21 | Windows не noop; ошибка native → exception | Probe `ThreadPowerThrottling`; раздельные capability-флаги; отсутствие throttling не ошибка | [T-4.3](04-windows/T-4.3-windows-capability-probe.md) |
| §16 | get-then-set с копированием всех полей | Добавить `SCHED_FLAG_KEEP_POLICY | KEEP_PARAMS`; RT/DL потоки вне контракта | [D-04](00-decisions/D-04-linux-rt-threads.md), [T-5.5](05-linux-x86_64/T-5.5-linux-provider-set.md) |
| §15, §57, §58 | Linux «Supported if kernel supports uclamp» | Оговорка про governor и топологию; `util_max` не ограничивает CPU-долю | [D-05](00-decisions/D-05-linux-lower-classes-policy.md), [T-5.8](05-linux-x86_64/T-5.8-linux-docs.md) |
| §23, §24 | Поведение при ошибке в `wrap()` не задано | Строгий `wrap()` + `trySet()` | [D-02](00-decisions/D-02-wrap-failure-policy.md), [T-2.5](02-public-api/T-2.5-thread-qos-facade.md) |
| §20 | `wrap()` может вернуть исходный task | Всегда оборачивать ради единого контракта | [D-03](00-decisions/D-03-wrap-on-unsupported.md), [T-2.5](02-public-api/T-2.5-thread-qos-facade.md) |
| §4 | Четыре boolean-поля без семантики | `architecture`, `unsupportedReason`, раздельные Windows-флаги | [D-06](00-decisions/D-06-capabilities-shape.md), [T-2.2](02-public-api/T-2.2-qos-capabilities.md) |
| §18 | Probe при первом `set()` | Probe также из `isSupported()` / `capabilities()`; детект по возвращённому `size` | [T-5.4](05-linux-x86_64/T-5.4-linux-capability-probe.md) |
| §19, §27 | Захват errno без указания владения segment | Per-call arena, статический segment запрещён | [T-2.8](02-public-api/T-2.8-ffm-support.md) |
| §78, §27 | Lazy init и отсутствие lock без механизма | Holder idiom | [T-2.4](02-public-api/T-2.4-provider-factory.md) |
| новый раздел | Нет минимальных версий ОС | Раздел с версиями; native-image вне scope | [D-07](00-decisions/D-07-minimum-os-versions.md) |
| §3.1, §7.2 | `DEFAULT` как reset | Документировать `DEFAULT ≠ UNSPECIFIED`, `EPERM`, I/O throttling `BACKGROUND` | [T-3.5](03-macos/T-3.5-macos-docs.md) |
| §9.1, §83 | Приоритет Windows упомянут вскользь | Документировать взаимодействие с HotSpot priority и риск `HIGHEST` | [T-4.6](04-windows/T-4.6-windows-docs.md) |
| §14 | x86_64 и aarch64 | riscv64 / loongarch64 опционально через asm-generic | [T-5.2](05-linux-x86_64/T-5.2-linux-syscall-numbers.md) |
