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
- ✅ 잠금 상태 실시간 리포트
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

## 페어링 방법

1. SmartThings에서 기기 삭제
2. 도어락 전면 초기화 홀에 핀으로 꽂아 기기 초기화
3. 배터리 커버를 열고: 등록 버튼 1번 → 비밀번호 4번(관리자 확인) → 무선연동(1번) 누른 상태에서
   Hubitat과 페어링 시작 → Discover Devices

## 참고 / 출처

- 삼성SDS 공식 SmartThings DeviceTypeHandler 소스 (Apache-2.0)
- 모두의 스마트홈 카페 - 스피드박님 원본 엣지드라이버 자료
- 모두의 스마트홈 카페 - 풀스타님, 땡깡둥이님 조언

## 라이선스

Apache-2.0 (참고한 삼성SDS 원본 코드의 라이선스를 따름)
