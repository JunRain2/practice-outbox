# Outbox Pattern

Transactional Outbox Pattern 구현체. Aggregate 저장과 이벤트 발행의 원자성을 보장하며, at-least-once 전달을 실현한다.

카프카/MQ 메시지 발행뿐 아니라 **외부 API 호출**까지 고려한 설계.

## 기술 스택

- Kotlin, Spring Boot 4, JPA, MySQL 8
- Redis 7 (Redisson) — 분산 락

## 이벤트 흐름

### 경로 1 — Push (즉시 처리)

```
MockService.createMock()
  └─ @Transactional
      ├─ Aggregate 저장 (Mock)
      └─ Outbox 저장 (Event) + ApplicationEvent 발행
          └─ [커밋 후] EventListener (@Async)
              └─ EventProcessor.process() ── DB 락 or Redis 분산 락
```

트랜잭션 커밋 후 `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`로 즉시 비동기 처리를 시도한다.

### 경로 2 — Pull (스케줄러 복구)

```
OutboxEventScheduler (@Scheduled)
  └─ EventExecutor.execute()
      └─ PENDING 이벤트 조회 (grace period 적용)
          └─ EventProcessor.process() ── DB 락 or Redis 분산 락

StuckEventRecoveryScheduler (@Scheduled)
  └─ StuckEventRecoveryExecutor.process()
      └─ PROGRESSING + timeout 초과 이벤트 → PENDING 복구
```

Listener가 실패하거나 누락된 이벤트를 Scheduler가 주기적으로 수거한다.

## 아키텍처 구조

```
com.junrain.outbox
├── domain/                          # 도메인 계층
│   ├── BaseEntity.kt                #   JPA 공통 엔티티
│   ├── LockManager.kt               #   분산 락 인터페이스
│   └── mock/
│       ├── Mock.kt                   #   Aggregate Root
│       ├── MockRepository.kt        #   Repository 인터페이스
│       └── MockEventPublisher.kt    #   이벤트 발행 인터페이스
├── application/                     # 애플리케이션 계층
│   └── mock/
│       ├── MockService.kt           #   유스케이스 오케스트레이션
│       └── command/                  #   Command DTO
└── infra/                           # 인프라 계층
    ├── config/                       #   설정 (EventProperties, Scheduler, Async, JPA)
    ├── event/                        #   Outbox 이벤트 처리
    │   ├── Event.kt                  #     Outbox 테이블 엔티티
    │   ├── EventRepository.kt       #     이벤트 조회
    │   ├── EventExecutor.kt         #     배치 오케스트레이션
    │   ├── EventListener.kt          #     커밋 후 비동기 처리 (공통)
    │   ├── EventProcessor.kt        #     개별 이벤트 처리 인터페이스
    │   ├── DbLockEventProcessor.kt  #     DB 락 구현 (UPDATE WHERE 방식)
    │   ├── RedisLockEventProcessor.kt #   Redis 분산 락 구현
    │   └── mock/                     #     Mock 이벤트 구현
    │       └── MockEventPublisherImpl.kt  # Outbox 저장 + ApplicationEvent 발행
    │   ├── StuckEventRecoveryExecutor.kt #  stuck 이벤트 복구
    ├── redis/
    │   └── RedisLockManager.kt      #   LockManager 구현 (Redisson)
    └── scheduler/
        ├── OutboxEventScheduler.kt  #   이벤트 폴링 스케줄러
        └── StuckEventRecoveryScheduler.kt # stuck 이벤트 복구 스케줄러
```

## Event 엔티티

Outbox 테이블의 구조:

| 컬럼 | 설명 |
|------|------|
| `aggregate_type` | Aggregate 종류 (MOCK) |
| `aggregate_id` | Aggregate ID |
| `event_type` | 이벤트 종류 (MOCK_CREATED) |
| `payload` | JSON 직렬화된 이벤트 데이터 |
| `status` | PENDING / PROGRESSING / SUCCEEDED / FAILED |
| `attempt_count` | 시도 횟수 |
| `next_attempt_at` | 다음 재시도 시각 (지수 백오프) |
| `attempted_at` | 마지막 처리 시도 시각 |

### 상태 전이

```
PENDING ──(DB 락 선점)──→ PROGRESSING ──(성공)──→ SUCCEEDED
                              │
                              └──(실패, attemptCount < maxCount)──→ PENDING (nextAttemptAt 갱신)
                              │
                              └──(실패, attemptCount >= maxCount)──→ FAILED
                              │
                              └──(서버 장애, timeout 초과)──→ PENDING (StuckEventRecovery)
```

## 설계 결정

### Outbox 테이블 방식 (vs CDC)

CDC(Change Data Capture)는 DB 변경 로그를 감지하여 이벤트를 발행하는 방식이다. 하지만 이 프로젝트는 카프카/MQ 메시지 발행뿐 아니라 **외부 API 호출**까지 고려했기 때문에, 코드에서 명시적으로 이벤트를 제어할 수 있는 Outbox 테이블 방식을 선택했다.

### DB Lock (UPDATE WHERE)

`UPDATE Event SET status='PROGRESSING' WHERE id=:id AND status='PENDING'`으로 원자적으로 자원을 점유한다. `SELECT FOR UPDATE`가 아닌 이유는 애플리케이션에서 추가로 트랜잭션 범위를 지정해야 하기 때문이다.

PROGRESSING 상태가 도입되므로 서버 장애 시 이벤트가 PROGRESSING에 멈출 수 있다. `StuckEventRecoveryScheduler`가 `attemptedAt` 기준으로 timeout이 초과된 이벤트를 PENDING으로 복구한다.

Redis 같은 추가 인프라 의존이 불필요하다.

### Redis 분산 락

DB의 비관적 락이나 Named Lock을 사용하면 **DB 커넥션을 점유**하게 되어 병목이 발생할 수 있다. 락을 Redis로 분리하여 DB 커넥션 풀을 보호한다. 같은 이유로 Executor·Processor에 `@Transactional`을 걸지 않고, Processor가 개별 락 획득 후 상태를 재확인하는 방식으로 커넥션을 빠르게 반환한다.

### Event 엔티티를 infra에 배치

도메인 입장에서는 **"이벤트를 발행했다"는 사실만 알면 된다**. Outbox 테이블로 구현했는지, 카프카로 전송하는지는 인프라의 구현 상세다. at-least-once 보장이 필요 없다면 Outbox 패턴 자체가 불필요할 수 있으므로, 도메인이 이 구현에 의존하면 안 된다.

도메인은 `MockEventPublisher` 인터페이스만 알고, 인프라의 `MockEventPublisherImpl`이 Outbox 저장을 담당한다.

### Push + Pull 이중 경로

즉시 처리가 우선이다. Listener가 트랜잭션 커밋 직후 이벤트를 처리하고, **Scheduler는 at-least-once를 보장하기 위한 보험**이다. Listener가 실패하거나 애플리케이션이 재시작되어도 Scheduler가 미처리 이벤트를 수거한다.

### 스케줄러 글로벌 락 트레이드오프

개별 이벤트의 원자성은 Processor가 보장한다 (DB: `UPDATE WHERE status='PENDING'`, Redis: 분산 락). 두 구현 모두 락 획득에 실패하면 즉시 스킵하므로, 스케줄러 레벨의 글로벌 락이 없어도 중복 처리는 발생하지 않는다.

| | 글로벌 락 있음 | 글로벌 락 없음 (현재) |
|---|---|---|
| **폴링** | 단일 서버만 실행 | 모든 서버가 동시 폴링 |
| **처리량** | 단일 서버에 병목 | 서버 수만큼 병렬 처리 |
| **오버헤드** | 없음 | 중복 SELECT 발생 (처리는 안 됨) |
| **정합성** | Processor가 보장 | Processor가 보장 (동일) |

### Scheduler → Executor → Processor 분리

관심사 분리:
- **Scheduler**: 언제 실행할지 (fixedRate)
- **Executor**: 무엇을 처리할지 (PENDING 이벤트 조회 + 루프)
- **Processor**: 어떻게 처리할지 (개별 락 + 라우팅 + 재시도 + 상태 갱신)

### Grace Period

Scheduler는 N초 전에 생성된 이벤트만 조회한다 (기본 60초). 60초 이내에 Listener가 처리할 확률이 높으므로, 즉시 스케줄러를 돌리면 처리될 가능성이 높은 row까지 불필요하게 조회하게 된다. 이벤트가 많은 경우 이 조회가 DB 부담이 될 수 있다.

60초는 임의 선택이며, 운영 단계에서 트래픽과 처리 시간에 맞춰 튜닝한다.

### 지수 백오프 재시도

재시도 간격: `baseSeconds * 2^(attemptCount - 1)`

외부 시스템을 보호하기 위해 고정 간격 대신 지수 백오프를 적용했다. 추후 서킷브레이커 도입도 고려하고 있으며, 외부 API의 성격에 따라 전략은 달라질 수 있다. 현재는 베스트프랙티스를 적용한 상태.

이벤트 타입별로 별도의 재시도 전략을 설정할 수 있다.

### Payload를 JSON TEXT로 통일

이벤트 타입별로 별도 컬럼을 두는 대신, JSON 문자열 하나로 통일했다. 이벤트 타입이 늘어나도 Outbox 테이블 스키마 변경 없이 대응할 수 있다.

## 설정

`outbox.lock-type`으로 락 전략을 선택한다. `db` 또는 `redis`.

### DB Lock 사용 시

```yaml
outbox:
  lock-type: db
  db-lock:
    recovery-timeout-seconds: 30   # stuck 이벤트 복구 주기 (초)
  scheduler:
    interval-seconds: 60           # 스케줄러 폴링 주기 (초) + grace period
  retry:
    default:
      max-count: 5
      base-seconds: 60
    strategies:
      MOCK_CREATED:
        max-count: 5
        base-seconds: 60
```

### Redis Lock 사용 시

```yaml
outbox:
  lock-type: redis
  scheduler:
    interval-seconds: 60
  retry:
    default:
      max-count: 5
      base-seconds: 60
    strategies:
      MOCK_CREATED:
        max-count: 5
        base-seconds: 60
```

`interval-seconds` 하나의 값이 fixedRate 주기와 grace period(`minusSeconds(N)`)를 동시에 제어한다.

## 테스트

| 테스트 | 검증 내용 |
|--------|----------|
| **MockServiceTest** | Aggregate+Event 원자적 저장, 트랜잭션 롤백 검증, 커밋 후 비동기 리스너 동작 |
| **EventExecutorTest** | PENDING 이벤트 조회·처리, 비PENDING 이벤트 무시, grace period 필터링, 처리 대상 없을 때 미호출, Listener 실패 후 Executor 복구 |
| **EventProcessorContractTest** | `EventProcessor` 계약 테스트 — `DbLock`·`RedisLock` 두 구현체를 `@TestFactory`로 순회하며 동일 계약 검증: 처리 성공/실패/재시도, 지수 백오프, 5회 초과 FAILED, 동일 이벤트 동시 처리 시 단일 스레드 보장, 처리 중 진입 차단, 서로 다른 이벤트 병렬 처리 |
| **StuckEventRecoveryExecutorTest** | stuck 이벤트 PENDING 복구, threshold 기준 필터링, 다른 상태 이벤트 미영향, 벌크 복구 행 수 반환, Executor 통합 |
| **RedisLockManagerTest** | 락 획득·해제·재획득, waitTime 초과 시 예외, leaseTime 자동 해제, 동시 접근 시 단일 스레드만 획득 |

Testcontainers(MySQL 8 + Redis 7)로 실제 인프라 환경에서 테스트하며, CyclicBarrier로 스레드를 동기화하여 동시성 시나리오를 검증한다.

## 주석 컨벤션

- **메서드 주석은 반환값 중심으로 작성한다.** 파라미터 전체를 나열하지 않고, 핵심 파라미터만 언급한다.
- **TODO 주석은 이상적인 상태를 먼저 쓰고, 왜 변경해야 하는지를 이어서 쓴다.**
- **인라인 주석은 "무엇을 하는지"보다 "왜 필요한지"를 쓴다.**

## TODO

- [ ] **비동기 처리 + 역압력 제어**: 현재 EventExecutor의 for 루프는 동기 순차 처리. 코루틴 기반 비동기 처리로 전환하되, 커넥션 풀 고갈을 방지하기 위해 역압력(backpressure) 메커니즘 적용
- [ ] **이벤트 타입 확장 시 OCP 구조**: 현재 EventProcessor의 `when (event.eventType)` 분기가 타입이 늘어날수록 비대해짐. EventHandler 인터페이스 + 전략 패턴으로 분리하여 새 이벤트 타입 추가 시 기존 코드 수정 없이 Handler만 추가하는 구조로 개선

## 실행 방법

```bash
# 인프라 실행
docker compose up -d

# 애플리케이션 실행
./gradlew bootRun

# 테스트
./gradlew test
```
