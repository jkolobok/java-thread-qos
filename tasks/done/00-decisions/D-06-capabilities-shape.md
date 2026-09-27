# D-06 Расширить QosCapabilities

- **Этап:** Этап 0. Решения и правки спецификации
- **Тип:** решение (до кода)
- **Спецификация:** §4, §65
- **Отклонение от спецификации:** да, см. раздел «Отклонения» в индексе
- **Зависит от:** нет
- **Блокирует:** [T-2.2](../02-public-api/T-2.2-qos-capabilities.md), [T-4.3](../04-windows/T-4.3-windows-capability-probe.md), [T-5.4](../05-linux-x86_64/T-5.4-linux-capability-probe.md)
- **Статус:** done

## Что сделать

Определить семантику полей на каждой ОС и добавить:

- `architecture`;
- `unsupportedReason` (строка, nullable);
- раздельные флаги `powerThrottlingSupported` и `priorityAdjustmentSupported` для Windows.

Логирование запрещено (§65), поэтому capabilities единственный канал диагностики «почему noop».

## Готово, когда

§4 содержит финальный record и таблицу «поле × ОС».

## Результат

§4 содержит финальный record (`architecture`, `powerThrottlingSupported`, `priorityAdjustmentSupported`, `unsupportedReason`), enum `Architecture {X86_64, AARCH64, OTHER}` и таблицу «поле × ОС».
