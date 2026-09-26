# D-07 Раздел «Минимальные версии ОС и рантаймов»

- **Этап:** Этап 0. Решения и правки спецификации
- **Тип:** решение (до кода)
- **Спецификация:** новый раздел рядом с §32, §60
- **Отклонение от спецификации:** да, см. раздел «Отклонения» в индексе
- **Зависит от:** нет
- **Блокирует:** [T-7.1](../07-docs-examples/T-7.1-readme.md)
- **Статус:** done

## Что сделать

Добавить в спецификацию:

- macOS 10.10+;
- Windows 10 1709+ для `ThreadPowerThrottling`; EcoQoS-семантика документирована для Windows 11 / Windows 10 21H2+ (сверить с Microsoft Learn);
- Linux 5.3+ с `CONFIG_UCLAMP_TASK`;
- GraalVM native-image объявить вне scope v1.

## Готово, когда

Раздел есть, ссылки на источники добавлены в §94.

## Результат

Добавлен §32.1 с таблицей минимальных версий (macOS 10.10; Windows 8 для priority, Windows 10 1709 для ThreadPowerThrottling, EcoQoS-семантика Win11 / Win10 21H2+; Linux 5.3 + CONFIG_UCLAMP_TASK; JDK 25; native-image вне scope). В §94 добавлены ссылки на GetThreadInformation, THREAD_INFORMATION_CLASS, sys/qos.h, UAPI sched.h, JEP 472.
