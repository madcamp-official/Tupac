---
name: login
when: 아이디와 비밀번호를 입력하는 로그인 화면
submit: yes
needs: username, password
---
## 클라우드
직접 채우지 말고 `need username password` 를 내세요. 값은 당신에게 오지 않고,
기기 안 모델이 이어받아 입력합니다.

## 기기
1. `type <아이디 칸> <username 값>`
   아이디 칸 = [type]인데 [type,비밀번호]가 아닌 칸.
2. `type <비밀번호 칸> <password 값>`
   비밀번호 칸 = [type,비밀번호]로 표시된 칸.
3. `tap <로그인 버튼>`
   "찾기", "만들기", "가입"이 들어간 버튼은 아닙니다.
4. `wait`
5. 로그인 화면이 사라졌으면 `done`. 그대로면 값이 틀린 것이니 다시 넣지 말고 `done`.
