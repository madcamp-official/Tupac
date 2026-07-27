---
name: form
when: 이름·연락처·주소 같은 개인정보 칸이 여럿인 입력 폼(배송지, 회원가입 등)
submit: no
needs: name, phone, postcode, address, address_detail
---
## 클라우드
직접 채우지 말고 필요한 값의 이름만 `need` 로 내세요.
예: `need name phone postcode address address_detail`.

## 기기
1. 칸 라벨과 "넣을 값"의 이름을 맞춥니다.
   "받는사람"→name, "연락처"→phone, "우편번호"→postcode, "상세주소"→address_detail.
2. 맞는 칸마다 `type <칸> <값>`. 한 스텝에 한 칸씩, 위에서부터.
3. 맞는 값이 없는 칸은 비워둡니다. 짐작으로 채우지 마세요.
4. 다 넣었으면 `done`. 저장·제출 버튼은 누르지 않습니다.
