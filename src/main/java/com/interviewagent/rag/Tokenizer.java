package com.interviewagent.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 关键词检索用的分词器，不依赖词典。
 * <ul>
 *   <li>英文和数字按连续字母数字切分并转小写，例如 "Full GC" → full、gc</li>
 *   <li>中文切成相邻两字的组合（bigram），例如 "分布式锁" → 分布、布式、式锁</li>
 * </ul>
 * 中文不像英文有空格分词，bigram 是不用词典时最常用的做法（Lucene 的 CJKAnalyzer 也是这样）。
 * 效果要求更高时，可以换成 IK、jieba 这类基于词典的分词器。
 */
public final class Tokenizer {

    private Tokenizer() {
    }

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        StringBuilder han = new StringBuilder();
        text.toLowerCase(Locale.ROOT).codePoints().forEach(cp -> {
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                flushWord(word, tokens);
                han.appendCodePoint(cp);
            } else if (Character.isLetterOrDigit(cp)) {
                flushHan(han, tokens);
                word.appendCodePoint(cp);
            } else {
                flushWord(word, tokens);
                flushHan(han, tokens);
            }
        });
        flushWord(word, tokens);
        flushHan(han, tokens);
        return tokens;
    }

    private static void flushWord(StringBuilder word, List<String> tokens) {
        if (!word.isEmpty()) {
            tokens.add(word.toString());
            word.setLength(0);
        }
    }

    private static void flushHan(StringBuilder han, List<String> tokens) {
        int[] chars = han.codePoints().toArray();
        if (chars.length == 1) {
            tokens.add(new String(chars, 0, 1));
        }
        for (int i = 0; i + 1 < chars.length; i++) {
            tokens.add(new String(chars, i, 2));
        }
        han.setLength(0);
    }
}
