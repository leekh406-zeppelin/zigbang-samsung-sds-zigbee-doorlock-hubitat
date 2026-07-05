# Samsung SDS Zigbee Door Lock Driver for Hubitat

Hubitat 커스텀 드라이버 — 삼성SDS 도어락(SHP-DP960 / SHP-960 Plus, Zigbee 모듈 DADT302)을
SmartThings 없이 Hubitat에 **Zigbee로 직결**하기 위한 드라이버입니다.

## 지원 모델

- SHP-DP960 / SHP-DP960SG
- SHP-960 Plus
- Zigbee 모듈: **DADT302**

같은 모듈 패밀리를 쓰는 다른 모델도 동작할 가능성이 높지만, 100% 검증된 것은 위 모델들입니다.

## 왜 커스텀 드라이버가 필요한가

이 락은 표준 ZCL Door Lock 클러스터(`0x0101`)의 공개 Unlock 커맨드(`0x01`)를 받으면
**응답은 SUCCESS로 오지만 실제 모터는 작동하지 않습니다.**

대신, 삼성SDS 자체 제조사 전용(manufacturer-specific) 커맨드로만 실제 개방이 됩니다:

- 클러스터: `0x0101`
- 커맨드: `0x1F`
- manufacturerCode: `0x0003`
- 페이로드: `[0x10, codeLength, ...PIN ASCII bytes]`

실제 테스트 결과 **빈 코드(length=0)로도 정상적으로 열립니다** (PIN 검증이 실질적으로 강제되지
않는 것으로 보입니다). 이 내용은 삼성SDS의 공식 SmartThings DTH 오픈소스
([samsung-smart-doorlock.groovy](https://github.com/SmartThingsCommunity/SmartThingsPublic/blob/master/devicetypes/samsungsds/samsung-smart-doorlock.src/samsung-smart-doorlock.groovy))
로도 확인되었습니다 (해당 코드는 하드코딩된 PIN "1235"를 사용).

## 지원 기능

- ✅ Lock / Unlock
- ✅ 잠금 상태 실시간 리포트 (자체 이벤트 알림 기반, 신뢰도 높음)
- ✅ 개폐 방식 구분 (keypad / fingerprint / rfid / bluetooth / manual / autolock) → `lastMethod`
- ✅ AutoLock(자동잠금) 이벤트 감지
- ✅ Contact(문 열림/닫힘) - IAS Zone 기반
- ✅ Tamper(강제 개방/탈거 시도) 감지
- ✅ 모델명 자동 조회
- ⚠️ setCode / deleteCode / nameSlot — 코드는 구현되어 있으나 실제 락에서 미검증
- ❌ 1회성 비밀번호 — 미구현

## 알려진 제약사항 (Known Limitations)

- **배터리 잔량 확인 불가.** 표준 배터리 % 속성(`0x0021`)은 미지원(status 0x86) 응답이 오고,
  전압 속성(`0x0020`)은 Configure Reporting은 성공하지만 실제 값이 항상 `0`으로 리턴됩니다.
  ZDO 바인딩을 명시적으로 재시도해봐도 동일했습니다. 커뮤니티의 다른 엣지드라이버 구현체에도
  배터리 관련 코드가 없는 것으로 확인되어, 이 하드웨어/펌웨어 자체의 한계로 보입니다.
  → **배터리 관리는 도어락 본체의 저전압 경고(LED/음성 안내)에 의존하는 것을 권장합니다.**

- **on-demand 상태 읽기(readAttribute) 불안정.** 이 락은 두 가지 경로로 잠금 상태를 알려주는데,
  Configure/Refresh 시 능동적으로 "지금 상태 뭐야?"라고 물어보는 readAttribute 응답이 가끔
  실제와 다른(stale) 값을 리턴하는 것이 확인되었습니다. 반면 락이 실제 동작마다 스스로 보내는
  Operation Event Notification(푸시 알림)은 모든 로그에서 100% 정확했습니다. v7부터는
  readAttribute 결과를 상태 갱신에 쓰지 않고 로그 확인용으로만 남기며, Operation Event만을
  유일한 상태 갱신 소스로 사용합니다.

## 페어링 방법

1. SmartThings에서 기기 삭제
2. 도어락 전면 초기화 홀에 핀으로 꽂아 기기 초기화
3. 배터리 커버를 열고: 등록 버튼 1번 → 비밀번호 4번(관리자 확인) → 무선연동(1번) 누른 상태에서
   Hubitat과 페어링 시작 → Discover Devices

## 버전 히스토리

- **v1**: 초기 버전 (기존 오픈소스 예제 기반)
- **v2**: IAS Zone enrollResponse 추가, zone status 파싱 버그 수정("0x" 접두사 처리),
  setCode() UserStatus/UserType 순서 수정
- **v3**: 표준 Unlock 커맨드가 응답만 성공하고 실제 모터가 안 움직이는 문제 발견 →
  제조사 전용 커맨드(cluster 0x0101, command 0x1F, mfgCode 0x0003)로 교체, 빈 코드로 동작 확인
- **v4**: 배터리 % 속성(0x0021) 미지원 확인 → 전압 속성(0x0020) 기반으로 전환,
  Operation Event 바이트 오프셋 버그 수정(AutoLock 이벤트가 "unknown"으로 누락되던 문제)
- **v5**: 삼성SDS 공식 SmartThings DTH 소스코드로 대조 검증 →
  배터리 전압→% 공식 교정(4V~6V 기준), Operation Event 코드 매핑 공식화(bluetooth 등 포함),
  모델명 조회 기능 추가 (실제 "SHP-DP960" 확인됨)
- **v6**: 배터리 값이 24시간+ 지나도 안 들어와서 명시적 ZDO 바인딩 시도 → 개선 없음
  (하드웨어/펌웨어 자체 한계로 결론, 커뮤니티 타 드라이버에도 배터리 미구현 확인)
- **v7**: readAttribute 기반 상태 조회가 가끔 실제와 다른 값을 리턴하는 버그 발견
  (문이 잠겨있는데 Refresh 시 "unlocked"로 표시되는 현상) → 상태 갱신을 Operation Event
  알림 단일 소스로 전환, readAttribute 결과는 로그 전용으로 변경

## 참고 / 출처

- 삼성SDS 공식 SmartThings DeviceTypeHandler 소스 (Apache-2.0)
- 모두의 스마트홈 카페 - 스피드박님 원본 엣지드라이버 자료
- 모두의 스마트홈 카페 - 풀스타님 자료, 땡깡둥이님 조언

## 라이선스

Apache-2.0 (참고한 삼성SDS 원본 코드의 라이선스를 따름)
