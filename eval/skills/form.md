---
name: form
when: 이름·연락처·주소 같은 개인정보 칸이 여럿인 입력 폼(배송지, 회원가입 등)
needs: name, phone, postcode, address, address_detail
---
**클라우드 모델**: 어떤 칸이 있는지 보고 필요한 필드만 골라 `need` 를 내세요.
예: `need name phone postcode address address_detail`. 값은 당신에게 오지 않습니다.

**기기 안 모델**: 아래 "넣을 값"을 받았을 것입니다.

1. 각 칸의 라벨을 "넣을 값"의 이름과 맞춰보세요.
   예: "받는사람" → name, "연락처" → phone, "우편번호" → postcode.
2. 맞는 칸마다 `tap <node>` 후 `type <node> <값>`. 위에서부터 한 스텝에 한 칸씩.
3. 받은 값 중에 맞는 칸이 없거나, 라벨이 어느 값과도 안 맞는 칸은 비워두세요.
   짐작으로 채우지 마세요.
4. 다 채웠으면 done. 저장·제출·결제 버튼은 누르지 마세요. 사람이 확인하고 누릅니다.
