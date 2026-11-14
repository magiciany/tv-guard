package com.hermes.tvguard;

import java.util.LinkedHashSet;
import java.util.Random;

/**
 * 题目生成：口算题（自动生成）+ 四级词汇题（英译中四选一）。
 * 选项统一为字符串；答错时用 reveal 展示正确答案。
 */
public final class Questions {

    public static final int TYPE_MIXED = 0;
    public static final int TYPE_MATH = 1;
    public static final int TYPE_VOCAB = 2;

    public static final class Q {
        /** 顶部小标题，如「口算」「四级词汇」。 */
        public final String kindLabel;
        /** 屏幕中央大字。 */
        public final String prompt;
        /** 四个选项文字。 */
        public final String[] options;
        /** 正确选项下标。 */
        public final int answer;
        /** 答错时展示的正确答案。 */
        public final String reveal;
        /** 副标题提示。 */
        public final String hint;

        Q(String kindLabel, String prompt, String[] options, int answer, String reveal, String hint) {
            this.kindLabel = kindLabel;
            this.prompt = prompt;
            this.options = options;
            this.answer = answer;
            this.reveal = reveal;
            this.hint = hint;
        }
    }

    private static final Random R = new Random();

    private Questions() {
    }

    public static Q random(int type, int difficulty) {
        if (type == TYPE_MATH) {
            return math(difficulty);
        }
        if (type == TYPE_VOCAB) {
            return vocab();
        }
        return R.nextBoolean() ? vocab() : math(difficulty);
    }

    // ---------------- 四级词汇 ----------------

    public static Q vocab() {
        String[][] bank = VocabBank.BANK;
        int idx = R.nextInt(bank.length);
        String word = bank[idx][0];
        String meaning = bank[idx][1];

        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(meaning);
        int guard = 0;
        while (set.size() < 4 && guard++ < 1000) {
            String cand = bank[R.nextInt(bank.length)][1];
            if (!cand.equals(meaning)) {
                set.add(cand);
            }
        }
        String[] opts = shuffle(set.toArray(new String[0]));
        int ans = 0;
        for (int i = 0; i < opts.length; i++) {
            if (opts[i].equals(meaning)) {
                ans = i;
            }
        }
        return new Q("四级词汇", word, opts, ans,
                word + " = " + meaning, "请选择这个单词的中文释义");
    }

    // ---------------- 口算 ----------------

    public static Q math(int difficulty) {
        int value;
        String text;
        if (difficulty <= 1) {
            int a = 11 + R.nextInt(79);
            int b = 11 + R.nextInt(79);
            value = a + b;
            text = a + " + " + b + " = ?";
        } else if (difficulty == 2) {
            int a = 3 + R.nextInt(9);
            int b = 3 + R.nextInt(9);
            int c = 5 + R.nextInt(40);
            value = a * b + c;
            text = a + " × " + b + " + " + c + " = ?";
        } else {
            int a = 3 + R.nextInt(9);
            int b = 2 + R.nextInt(9);
            int c = 2 + R.nextInt(20);
            value = a * (b + c);
            text = a + " × (" + b + " + " + c + ") = ?";
        }

        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(String.valueOf(value));
        int guard = 0;
        while (set.size() < 4 && guard++ < 2000) {
            int d = 1 + R.nextInt(12);
            if (R.nextBoolean()) {
                d = -d;
            }
            int cand = value + d;
            if (cand > 0) {
                set.add(String.valueOf(cand));
            }
        }
        int extra = 1;
        while (set.size() < 4) {
            set.add(String.valueOf(value + 37 * extra++));
        }

        String[] opts = shuffle(set.toArray(new String[0]));
        int ans = 0;
        String target = String.valueOf(value);
        for (int i = 0; i < opts.length; i++) {
            if (opts[i].equals(target)) {
                ans = i;
            }
        }
        return new Q("口算", text, opts, ans,
                text.replace("?", target), "答对才能继续使用");
    }

    private static String[] shuffle(String[] arr) {
        for (int i = arr.length - 1; i > 0; i--) {
            int j = R.nextInt(i + 1);
            String t = arr[i];
            arr[i] = arr[j];
            arr[j] = t;
        }
        return arr;
    }
}
