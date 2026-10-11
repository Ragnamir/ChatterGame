package com.chattergame.skeleton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Разбор Java-файла на строковые литералы и «код без строк и комментариев».
 *
 * Регулярки по сырому тексту здесь не годятся: SQL лежит в литералах, а обсуждение
 * запрещённых конструкций — в комментариях, и оба случая дают ложные срабатывания.
 * Поэтому файл разбирается посимвольно, с отслеживанием состояния. Маска сохраняет
 * длину исходника и позиции переводов строк, чтобы номера строк в отчётах совпадали
 * с файлом.
 */
final class SourceScanner {

    record Literal(int line, String text) {
    }

    record Finding(Path file, int line, String text) {
        @Override
        public String toString() {
            return file + ":" + line + " -> " + text.strip();
        }
    }

    private final String code;
    private final List<Literal> literals;
    private final int[] lineStarts;

    private SourceScanner(String code, List<Literal> literals, int[] lineStarts) {
        this.code = code;
        this.literals = literals;
        this.lineStarts = lineStarts;
    }

    static SourceScanner of(Path file) throws IOException {
        return of(Files.readString(file));
    }

    static SourceScanner of(String source) {
        int n = source.length();
        char[] out = source.toCharArray();
        List<Literal> literals = new ArrayList<>();
        int[] lineStarts = lineStarts(source, n);

        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                i = blankToEol(source, out, i, n);
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i = blankBlockComment(source, out, i, n);
            } else if (c == '"' && i + 2 < n
                    && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                i = takeTextBlock(source, out, i, n, literals, lineStarts);
            } else if (c == '"') {
                i = takeString(source, out, i, n, literals, lineStarts);
            } else if (c == '\'') {
                i = blankCharLiteral(source, out, i, n);
            } else {
                i++;
            }
        }
        return new SourceScanner(new String(out), List.copyOf(literals), lineStarts);
    }

    private static int blankToEol(String source, char[] out, int start, int n) {
        out[start] = ' ';
        out[start + 1] = ' ';
        int i = start + 2;
        while (i < n && source.charAt(i) != '\n') {
            out[i] = ' ';
            i++;
        }
        return i;
    }

    private static int blankBlockComment(String source, char[] out, int start, int n) {
        out[start] = ' ';
        out[start + 1] = ' ';
        int i = start + 2;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '*' && i + 1 < n && source.charAt(i + 1) == '/') {
                out[i] = ' ';
                out[i + 1] = ' ';
                return i + 2;
            }
            if (c != '\n') {
                out[i] = ' ';
            }
            i++;
        }
        return n;
    }

    private static int takeString(
            String source, char[] out, int start, int n, List<Literal> literals, int[] lineStarts) {
        StringBuilder value = new StringBuilder();
        out[start] = ' ';
        int i = start + 1;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < n) {
                value.append(source.charAt(i + 1));
                out[i] = ' ';
                out[i + 1] = ' ';
                i += 2;
                continue;
            }
            if (c == '"' || c == '\n') {
                out[i] = ' ';
                literals.add(new Literal(lineOf(lineStarts, start), value.toString()));
                return i + 1;
            }
            value.append(c);
            out[i] = ' ';
            i++;
        }
        literals.add(new Literal(lineOf(lineStarts, start), value.toString()));
        return n;
    }

    private static int takeTextBlock(
            String source, char[] out, int start, int n, List<Literal> literals, int[] lineStarts) {
        StringBuilder value = new StringBuilder();
        Arrays.fill(out, start, Math.min(start + 3, n), ' ');
        int i = start + 3;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < n) {
                value.append(source.charAt(i + 1));
                out[i] = ' ';
                out[i + 1] = ' ';
                i += 2;
                continue;
            }
            if (c == '"' && i + 2 < n
                    && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                Arrays.fill(out, i, Math.min(i + 3, n), ' ');
                literals.add(new Literal(lineOf(lineStarts, start), value.toString()));
                return i + 3;
            }
            if (c != '\n') {
                out[i] = ' ';
            }
            value.append(c);
            i++;
        }
        literals.add(new Literal(lineOf(lineStarts, start), value.toString()));
        return n;
    }

    private static int blankCharLiteral(String source, char[] out, int start, int n) {
        out[start] = ' ';
        int i = start + 1;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < n) {
                out[i] = ' ';
                out[i + 1] = ' ';
                i += 2;
                continue;
            }
            if (c == '\'' || c == '\n') {
                out[i] = ' ';
                return i + 1;
            }
            out[i] = ' ';
            i++;
        }
        return n;
    }

    private static int[] lineStarts(String source, int n) {
        int[] starts = new int[Math.max(2, n)];
        starts[0] = 0;
        int count = 1;
        for (int i = 0; i < n; i++) {
            if (source.charAt(i) == '\n') {
                starts[count++] = i + 1;
            }
        }
        return Arrays.copyOf(starts, count);
    }

    static int lineOf(int[] lineStarts, int index) {
        int pos = Arrays.binarySearch(lineStarts, index);
        return pos >= 0 ? pos + 1 : -pos - 1;
    }

    int lineOf(int index) {
        return lineOf(lineStarts, index);
    }

    /** Исходник без литералов и комментариев: годен для сопоставления скобок и аннотаций. */
    String code() {
        return code;
    }

    /** Все строковые литералы, включая текстовые блоки. */
    List<Literal> literals() {
        return literals;
    }

    /** Диапазоны [открывающая скобка, закрывающая скобка] тел после каждого вхождения аннотации. */
    List<int[]> bodyRangesAfter(String annotation) {
        List<int[]> ranges = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = code.indexOf(annotation, from);
            if (at < 0) {
                return ranges;
            }
            int open = code.indexOf('{', at);
            if (open < 0) {
                return ranges;
            }
            int depth = 0;
            int end = -1;
            for (int j = open; j < code.length(); j++) {
                char c = code.charAt(j);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        end = j;
                        break;
                    }
                }
            }
            if (end < 0) {
                return ranges;
            }
            ranges.add(new int[] {open, end});
            from = end;
        }
    }

    static List<Path> javaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }
}
