---
name: book-megabox-movie
description: Navigate the Megabox Android app to the final payment boundary.
---

# Megabox booking

- Never click the final payment or purchase action.
- For a requested city, do not substitute a similarly named theater or another city.
- Default to one adult and the available general seat nearest the auditorium center.
- Prefer the theater-first route through the exact bottom navigation node whose view id is
  `com.megabox.mop:id/booking`. Do not use the header ticket icon.
- Verify that booking entry shows `극장별 예매`, `영화별 예매`, and `극장 선택`.
- Select the requested region and exact theater, then select the requested date, exact movie,
  and a showtime within the requested interval.
- Before tapping a showtime, collect every showtime node exposed in the current
  observation. Parse each candidate as `HH:mm`, compare numeric clock minutes
  independently of display/tree order, filter by the requested interval (and
  by the current time when the goal requires the next available screening),
  and choose the minimum eligible value. Do not tap a showtime until the
  current snapshot's candidate set has been checked.
- Once the exact theater is selected and `선택 완료` shows a nonzero selection
  count such as `(1/5)`, click `선택 완료`. Do not tap the same theater again;
  that deselects it and creates a two-screen toggle loop.
- The region column and theater column are separate. After selecting
  `부산/대구/경상`, scroll only the theater-list container vertically.
- To reveal later theaters, swipe upward inside the right theater list: keep X inside the
  right column and use `start_y > end_y`. A horizontal swipe cannot reveal later theaters.
- If a swipe reports success but the screen fingerprint does not change, do not repeat the
  same gesture. Reverse an incorrect direction, choose the actual scrollable theater
  container, use another grounded control, or switch to the movie-first route.
- Treat a stretch/bounce effect with an unchanged fingerprint as overscroll, not progress.
- After selecting the showtime, handle notices, choose one adult, agree to seat selection,
  and choose only a seat marked `판매가능 일반` nearest the geometric center.
- Stop successfully when `결제하기` is visible. Do not tap it.
