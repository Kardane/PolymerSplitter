# PolymerSplitter

[English](README.md) | [한국어](README.ko.md)

[Polymer](https://github.com/Patbox/polymer)가 생성한 리소스 팩을 네임스페이스별로 분할하여 클라이언트가 독립적으로 캐싱할 수 있도록 지원하는 서버 전용 패브릭(Fabric) 컴패니언 모드입니다. 리소스 팩의 호스팅 및 전송은 Polymer AutoHost에 위임합니다.

리소스 팩이 변경되었을 때 전체 팩을 다시 받지 않고, 변경된 네임스페이스 팩만 선별하여 다시 다운로드합니다.

**개발 상태:** pre-0.1.0 개발 단계 (Phase 10–14 안정성 기준선 완료)  
**클라이언트 모드:** 불필요 (순정 바닐라 클라이언트 접속 지원)

---

## 주요 기능

- **네임스페이스 단위 분할:** Polymer의 최종 통합 팩을 리소스 네임스페이스별 ZIP 파일로 분할합니다.
- **사운드 팩 전용 분리:** 용량이 큰 `assets/minecraft/sounds/` 경로의 OGG 음원을 독립된 `minecraft.sounds` 팩으로 분리합니다 (`sounds.json`은 기본 팩에 유지).
- **기본 팩 메타데이터 보존:** 루트 메타데이터(`pack.mcmeta`, `pack.png`), 라이선스 파일, 미선언 오버레이, 안전한 비네임스페이스 파일은 기본 팩(`minecraft` 또는 알파벳순 첫 번째 네임스페이스)에 안전하게 보존합니다.
- **결정론적 압축 및 콘텐츠 주소 지정:** 고정 타임스탬프(1980-01-01), 정렬된 엔트리 순서, 인스트림 SHA-1 해시 계산을 통해 불필요한 해시 변경을 방지하고 빠른 캐시 검증을 지원합니다.
- **무중단 폴백(Fallback):** 팩 분할, 캐시, 또는 프로바이더 오류 발생 시 Polymer 본래의 단일 팩 전송 방식으로 즉시 안전하게 전환됩니다.

---

## 지원 버전

| 마인크래프트 | 빌드 타깃 | 자바 | Polymer |
| --- | --- | ---: | --- |
| 1.21.8 | 1.21.8 | 21 | 0.13.13+1.21.8 |
| 1.21.9–1.21.10 | 1.21.10 | 21 | 0.14.4+1.21.10 |
| 1.21.11 | 1.21.11 | 21 | 0.15.2+1.21.11 |
| 26.1–26.1.2 | 26.1.2 | 25 | 0.16.5+26.1.2 |
| 26.2 | 26.2 | 25 | 0.17.5+26.2 |
| 26.3+ | 26.3 | 25 | 0.18.2+26.3 |

---

## AutoHost 호환성

Polymer의 내장 로컬 AutoHost 프로바이더와 완벽히 연동됩니다:

- `polymer:automatic`
- `polymer:auto`
- `polymer:netty`
- `polymer:same_port`
- `polymer:http_server`
- `polymer:standalone`

*참고:* AutoHost가 비활성화되어 있거나 `polymer:external`, `polymer:empty`, 알 수 없는 커스텀 프로바이더를 사용할 경우 팩 분할 전송이 비활성화되며, Polymer 본래의 전송 경로가 그대로 유지됩니다. 외부 호스팅 업로드는 지원하지 않습니다.

---

## 명령어

모든 명령어는 관리자 권한(레벨 2 이상 또는 최신 권한 프리디케이트)이 필요합니다.

| 명령어 | 설명 |
| --- | --- |
| `/polymersplitter status` | 현재 분할 상태, 캐시 상태 및 활성 AutoHost 프로바이더 정보를 확인합니다. |
| `/polymersplitter list` | 활성화된 분할 네임스페이스 목록과 팩 크기를 표시합니다. |
| `/polymersplitter reload` | `config/polymersplitter.json` 설정 파일을 다시 불러옵니다. |
| `/polymersplitter rebuild` | Polymer 팩 재생성을 요청한 후 분할 배포 파이프라인을 실행합니다. |
| `/polymersplitter send <targets> all` | 대상 플레이어에게 현재 활성화된 모든 분할 팩을 전송합니다. |
| `/polymersplitter send <targets> namespace <namespace>` | 대상 플레이어에게 특정 네임스페이스 팩만 전송합니다. |

`<targets>` 인자는 바닐라 플레이어 셀렉터(`@a`, `@p`, `@r`, 태그 필터 등)를 그대로 지원합니다. `READY` 상태인 네임스페이스는 자동 완성으로 입력할 수 있습니다.

---

## 설정

설정 파일은 `config/polymersplitter.json`에 위치하며, 첫 실행 시 기본값으로 자동 생성됩니다:

```json
{
  "enabled": true,
  "splitMode": "namespace",
  "copyPackIcon": true,
  "deterministicZip": true,
  "logPackSizes": true,
  "minSplitPackSizeMb": 30,
  "compressionLevel": 6,
  "includeNamespaces": [],
  "excludeNamespaces": [],
  "unreferencedBlobRetentionDays": 0
}
```

### 설정 항목

| 항목 | 타입 | 기본값 | 설명 |
| --- | --- | ---: | --- |
| `enabled` | boolean | `true` | 모드 활성화 여부. 변경 시 서버 재시작이 필요합니다. |
| `splitMode` | string | `"namespace"` | 분할 방식. 현재는 `"namespace"`만 지원합니다. |
| `copyPackIcon` | boolean | `true` | 분할된 각 팩에 `pack.png`를 복사해 포함할지 여부. |
| `deterministicZip` | boolean | `true` | 고정 타임스탬프(1980-01-01)와 정렬된 파일 순서를 적용하여 재현 가능한 ZIP 생성. |
| `logPackSizes` | boolean | `true` | 팩 생성 시 압축 전/후 크기 로그 출력 여부. |
| `minSplitPackSizeMb` | integer | `30` | 비기본 팩의 최소 압축 크기(MiB). 이 값 이하의 작은 팩은 기본 팩으로 병합됩니다 (`0` 설정 시 비활성화). |
| `compressionLevel` | integer | `6` | ZIP Deflate 압축 레벨 (`0`–`9`). |
| `includeNamespaces` | string[] | `[]` | 분할을 허용할 네임스페이스 허용 목록. 비어있지 않으면 목록에 없는 네임스페이스는 기본 팩으로 병합됩니다. |
| `excludeNamespaces` | string[] | `[]` | 기본 팩으로 강제 병합할 네임스페이스 제외 목록. 제외 목록이 허용 목록보다 항상 우선합니다. |
| `unreferencedBlobRetentionDays` | integer | `0` | 서버 시작 시 참조되지 않는 오래된 블롭 GC 보관 일수: `0`은 즉시 삭제, `>0`은 지정 일수 동안 보관, `-1`은 GC 비활성화. |

*동작 규칙:*
- 정책 필터는 에셋을 임의로 삭제하지 않으며, 제외되거나 기준 용량 이하인 팩은 기본 팩으로 안전하게 병합됩니다.
- `minecraft.sounds`는 고유 키 및 `minecraft` 원본 네임스페이스 정책을 모두 상속받습니다.
- 출력에 영향을 주는 설정(`copyPackIcon`, `deterministicZip`, `minSplitPackSizeMb`, `compressionLevel`, 네임스페이스 필터)이 변경되면 기존 캐시는 무효화되며 다음 생성부터 반영됩니다. 즉시 적용하려면 `/polymersplitter rebuild`를 실행하세요.

---

## 빌드 방법

JDK 25가 필요합니다.

```bash
# 전체 버전 빌드
./gradlew build

# 특정 버전 개별 빌드
./gradlew :mc-1.21.8:build
./gradlew :mc-1.21.10:build
./gradlew :mc-1.21.11:build
./gradlew :mc-26.1:build
./gradlew :mc-26.2:build
./gradlew :mc-26.3:build
```

---

## 관련 문서

- [ARCHITECTURE.md](ARCHITECTURE.md) — 런타임 흐름, 캐시 불변식, 스토리지 구조 및 내부 설계
- [AGENTS.md](AGENTS.md) — 코딩 에이전트를 위한 저장소 규칙 및 개발 가이드
- [POLYMER_COMPATIBILITY.md](POLYMER_COMPATIBILITY.md) — 업스트림 Polymer API 호환성 점검표
- [ROADMAP.md](ROADMAP.md) — 완료된 마일스톤 및 향후 개발 로드맵
