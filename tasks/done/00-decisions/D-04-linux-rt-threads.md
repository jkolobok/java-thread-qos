# D-04 Linux: потоки с RT/deadline policy

- **Этап:** Этап 0. Решения и правки спецификации
- **Тип:** решение (до кода)
- **Спецификация:** §16
- **Отклонение от спецификации:** да, см. раздел «Отклонения» в индексе
- **Зависит от:** нет
- **Блокирует:** [T-5.5](../05-linux-x86_64/T-5.5-linux-provider-set.md)
- **Статус:** done

## Что сделать

Определить поведение `set()` для потока с policy, отличной от `SCHED_OTHER`/`SCHED_BATCH`/`SCHED_IDLE`.

Рекомендация: бросать `ThreadQosException` с `nativeOperation="sched_setattr"` и понятным сообщением, потому что:

- для RT-потоков ядро держит собственный default `util_min`, и explicit `DEFAULT` его затрёт;
- для `SCHED_DEADLINE` с суженной affinity любой `sched_setattr` даёт `EPERM`.

## Готово, когда

§16 дополнен, поведение попало в тесты этапа 5 (T-5.6).

## Результат

Добавлен §16.1: policy вне `{SCHED_OTHER, SCHED_BATCH, SCHED_IDLE}` → `ThreadQosException(nativeOperation="sched_setattr", nativeErrorCode=null)`. В §16 зафиксированы флаги `SCHED_FLAG_KEEP_POLICY | SCHED_FLAG_KEEP_PARAMS`.
