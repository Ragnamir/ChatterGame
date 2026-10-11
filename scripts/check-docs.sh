#!/usr/bin/env bash
# Проверяет согласованность зафиксированной документации. Запускается до E01 и на каждом гейте.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
fail=0

err() { echo "FAIL: $1"; fail=1; }
ok()  { echo "  ok: $1"; }

DOCS=(AGENTS.md docs/REQUIREMENTS.md docs/PLAN.md docs/STATUS.md docs/DECISIONS.md)

# 1. Каждая ссылка D-xxx имеет определение в DECISIONS.md
defined=$(grep -oE '^\*\*D-[0-9]{3}' docs/DECISIONS.md | grep -oE 'D-[0-9]{3}' | sort -u)
referenced=$(grep -ohE 'D-[0-9]{3}' "${DOCS[@]}" | sort -u)
dangling=$(comm -23 <(echo "$referenced") <(echo "$defined"))
if [ -n "$dangling" ]; then
  err "ссылки без определения: $(echo "$dangling" | tr '\n' ' ')"
else
  ok "все $(echo "$referenced" | wc -l) ссылок на решения определены ($(echo "$defined" | wc -l) всего)"
fi

# 2. Каждый этап из карты имеет раздел с детализацией
map_stages=$(sed -n 's/^| \([EFOS][0-9][0-9]\) | .*/\1/p' docs/PLAN.md | sort -u)
detail_stages=$(sed -n 's/^### \([EFOS][0-9][0-9]\) .*/\1/p' docs/PLAN.md | sort -u)
missing_detail=$(comm -23 <(echo "$map_stages") <(echo "$detail_stages"))
extra_detail=$(comm -13 <(echo "$map_stages") <(echo "$detail_stages"))
[ -n "$missing_detail" ] && err "нет разделов этапов: $(echo "$missing_detail" | tr '\n' ' ')"
[ -n "$extra_detail" ] && err "этапы вне карты: $(echo "$extra_detail" | tr '\n' ' ')"
if [ -z "$missing_detail$extra_detail" ]; then
  ok "карта этапов и детализация совпадают ($(echo "$map_stages" | wc -l) этапов)"
fi

# 3. В каждом разделе этапа есть «Критерии приёмки»
no_ac=""
for s in $detail_stages; do
  body=$(sed -n "/^### $s /,/^### \|^## /p" docs/PLAN.md)
  echo "$body" | grep -q 'Критерии приёмки' || no_ac="$no_ac $s"
done
[ -n "$no_ac" ] && err "этапы без критериев приёмки:$no_ac" || ok "критерии приёмки есть у всех этапов"

# 4. Нет следов отвергнутых формулировок
stale=$(grep -nE 'NO_JOIN[^E]|character_id, chat_id, seq|не требуется для старых сообщений|N часов|таймаут не сжигает|не подтверждён' "${DOCS[@]}" || true)
[ -n "$stale" ] && { err "устаревшие формулировки:"; echo "$stale"; } || ok "нет следов отвергнутых решений"

# 5. Решения, порождённые самопроверкой, помечены как не обсуждённые владельцем
if grep -q 'а не обсуждением' docs/DECISIONS.md; then
  ok "решения самопроверки помечены отдельно от обсуждённых"
else
  err "нет пометки о происхождении решений D-086/D-087"
fi

# 6. Открытых вопросов быть не должно: все решения приняты
if grep -q '## Открытые решения' docs/DECISIONS.md; then
  open_refs=$(sed -n '/## Открытые решения/,$p' docs/DECISIONS.md | grep -cE '^\*\*D-[0-9]{3}' || true)
  if [ "$open_refs" -eq 0 ]; then
    ok "открытых решений нет, раздел оставлен с пояснением"
  else
    err "остались не принятые решения: $open_refs"
  fi
else
  err "нет раздела «Открытые решения»"
fi

# 8. Ключевые лимиты присутствуют (регрессия после правок решений)
for pair in "docs/REQUIREMENTS.md:8 до 128" "docs/REQUIREMENTS.md:30 дней" \
            "docs/REQUIREMENTS.md:chat/completions" "docs/REQUIREMENTS.md:до 4000 знаков" \
            "docs/REQUIREMENTS.md:одна картинка на сущность" "docs/PLAN.md:pg_dump" ; do
  file=${pair%%:*}; needle=${pair#*:}
  grep -qi -- "$needle" "$file" && ok "лимит на месте: $needle" \
    || err "потерян лимит «$needle» в $file"
done

# 7. STATUS.md фиксирует, что вопросы перед E01 закрыты
grep -q 'Закрытие вопросов перед E01' docs/STATUS.md \
  && ok "STATUS.md фиксирует закрытие вопросов перед E01" \
  || err "STATUS.md не фиксирует закрытие вопросов перед E01"

if [ "$fail" -eq 0 ]; then
  echo "DOC CHECK PASSED"
else
  echo "DOC CHECK FAILED"
fi
exit "$fail"
