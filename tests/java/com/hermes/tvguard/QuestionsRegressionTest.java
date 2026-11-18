package com.hermes.tvguard;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;

/** Plain-Java question invariants; run with scripts/test-regression.py. */
public final class QuestionsRegressionTest {
    public static void main(String[] args) throws Exception {
        Field random = Questions.class.getDeclaredField("R");
        random.setAccessible(true);
        ((Random) random.get(null)).setSeed(11002L);
        int checked = 0;
        for (int type = 0; type < 3; type++) {
            for (int difficulty = 1; difficulty <= 3; difficulty++) {
                for (int i = 0; i < 1000; i++) {
                    Questions.Q q = Questions.random(type, difficulty);
                    require(q.options.length == 4, "four options");
                    require(new HashSet<>(Arrays.asList(q.options)).size() == 4,
                            "unique options");
                    require(q.answer >= 0 && q.answer < 4, "valid answer index");
                    if ("口算".equals(q.kindLabel)) {
                        String expression = q.prompt.replace(" = ?", "");
                        String[] numbers = expression.replaceAll("[^0-9]+", " ")
                                .trim().split(" +");
                        int a = Integer.parseInt(numbers[0]);
                        int b = Integer.parseInt(numbers[1]);
                        int expected = numbers.length == 2 ? a + b
                                : expression.contains("(")
                                ? a * (b + Integer.parseInt(numbers[2]))
                                : a * b + Integer.parseInt(numbers[2]);
                        require(q.options[q.answer].equals(String.valueOf(expected)),
                                "correct arithmetic: " + q.prompt);
                    } else {
                        boolean matches = false;
                        for (String[] entry : VocabBank.BANK) {
                            if (entry[0].equals(q.prompt)
                                    && entry[1].equals(q.options[q.answer])) {
                                matches = true;
                                break;
                            }
                        }
                        require(matches, "correct vocabulary: " + q.prompt);
                    }
                    checked++;
                }
            }
        }
        System.out.println("PASS: " + checked + " seeded generated-question checks");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
