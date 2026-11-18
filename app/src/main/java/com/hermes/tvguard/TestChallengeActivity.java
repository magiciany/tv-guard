package com.hermes.tvguard;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Standalone preview: answers never modify enforcement; resume only pauses non-viewing time. */
public final class TestChallengeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Button[] options = new Button[4];
    private TextView title, prompt, countdown, reveal, footer;
    private Questions.Q question;
    private long lockedUntil;
    private String lockedReveal = "";
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (lockedUntil != 0L) {
                long left = lockedUntil - SystemClock.elapsedRealtime();
                if (left <= 0L) showQuestion();
                else countdown.setText("测试锁定：请等待 " + format(left));
            }
            handler.postDelayed(this, 400L);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_challenge);
        title = findViewById(R.id.tv_title);
        prompt = findViewById(R.id.tv_question);
        countdown = findViewById(R.id.tv_countdown);
        reveal = findViewById(R.id.tv_reveal);
        footer = findViewById(R.id.tv_footer);
        int[] ids = {R.id.opt0, R.id.opt1, R.id.opt2, R.id.opt3};
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            options[i] = findViewById(ids[i]);
            options[i].setOnClickListener(v -> choose(index));
        }
        if (saved != null) {
            lockedUntil = saved.getLong("testLockedUntil", 0L);
            lockedReveal = saved.getString("testReveal", "");
        }
        if (lockedUntil > SystemClock.elapsedRealtime()) showLocked();
        else showQuestion();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putLong("testLockedUntil", lockedUntil);
        out.putString("testReveal", lockedReveal);
    }

    @Override protected void onResume() {
        super.onResume();
        // Pause non-viewing time even when returning directly to an existing preview task.
        GuardService service = GuardService.INSTANCE;
        if (service != null) service.pauseForSettings();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    private void showQuestion() {
        lockedUntil = 0L;
        lockedReveal = "";
        question = Questions.random(Prefs.getType(this), Prefs.getDifficulty(this));
        title.setText("测试验证 · " + question.kindLabel);
        title.setTextColor(getResources().getColor(R.color.guard_accent));
        prompt.setVisibility(View.VISIBLE);
        prompt.setText(question.prompt);
        countdown.setVisibility(View.GONE);
        reveal.setVisibility(View.GONE);
        footer.setText(question.hint + "（仅测试，返回键退出）");
        for (int i = 0; i < options.length; i++) {
            options[i].setText(question.options[i]);
            options[i].setEnabled(true);
            options[i].setVisibility(View.VISIBLE);
        }
        options[0].requestFocus();
    }

    private void choose(int index) {
        if (lockedUntil != 0L || question == null) return;
        if (index == question.answer) {
            Toast.makeText(this, "测试答对，不影响守护计时或统计", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            lockedReveal = question.reveal;
            lockedUntil = SystemClock.elapsedRealtime() + Prefs.getLockMin(this) * 60_000L;
            showLocked();
        }
    }

    private void showLocked() {
        title.setText("测试锁定（不影响实际守护）");
        title.setTextColor(getResources().getColor(R.color.guard_danger));
        prompt.setVisibility(View.GONE);
        countdown.setVisibility(View.VISIBLE);
        countdown.setText("测试锁定：请等待 "
                + format(Math.max(0L, lockedUntil - SystemClock.elapsedRealtime())));
        reveal.setVisibility(View.VISIBLE);
        reveal.setText("正确答案：" + lockedReveal);
        footer.setText("仅模拟锁定，返回键可退出测试");
        for (Button option : options) {
            option.setEnabled(false);
            option.setText("—");
        }
    }

    private static String format(long ms) {
        long seconds = (ms + 999L) / 1000L;
        return String.format(Locale.CHINA, "%d 分 %02d 秒", seconds / 60L, seconds % 60L);
    }
}
