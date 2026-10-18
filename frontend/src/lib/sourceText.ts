/**
 * Снимает комментарии, сохраняя строки и шаблоны: инвариант `D-081` запрещает кириллицу
 * в литералах, но не в пояснениях к коду. Без этого шага проверка валила бы каждый
 * русский комментарий, и её бы отключили.
 */
export function stripComments(text: string): string {
  let out = '';
  let index = 0;
  while (index < text.length) {
    const char = text[index];
    const next = text[index + 1];

    if (char === '/' && next === '/') {
      while (index < text.length && text[index] !== '\n') {
        index += 1;
      }
      continue;
    }

    if (char === '/' && next === '*') {
      index += 2;
      while (index < text.length && !(text[index] === '*' && text[index + 1] === '/')) {
        index += 1;
      }
      index += 2;
      continue;
    }

    if (char === "'" || char === '"' || char === '`') {
      out += char;
      index += 1;
      while (index < text.length && text[index] !== char) {
        if (text[index] === '\\') {
          out += text[index];
          index += 1;
        }
        if (index < text.length) {
          out += text[index];
          index += 1;
        }
      }
      out += char;
      index += 1;
      continue;
    }

    out += char;
    index += 1;
  }
  return out;
}
