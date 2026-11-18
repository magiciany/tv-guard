package com.hermes.tvguard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** 全屏验证界面：D-pad 选择题；答对放行，答错进入锁定倒计时。 */
public class ChallengeActivity extends Activity {

    private static final String TAG = GuardService.TAG;

    private TextView tvTitle;
    private TextView tvQuestion;
    private TextView tvCountdown;
    private TextView tvReveal;
    private TextView tvFooter;
    private final Button[] opts = new Button[4];

    private Questions.Q question;
    private boolean lockedView = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            step();
            handler.postDelayed(this, 400L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        setContentView(R.layout.activity_challenge);

        tvTitle = findViewById(R.id.tv_title);
        tvQuestion = findViewById(R.id.tv_question);
        tvCountdown = findViewById(R.id.tv_countdown);
        tvReveal = findViewById(R.id.tv_reveal);
        tvFooter = findViewById(R.id.tv_footer);
        opts[0] = findViewById(R.id.opt0);
        opts[1] = findViewById(R.id.opt1);
        opts[2] = findViewById(R.id.opt2);
        opts[3] = findViewById(R.id.opt3);

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            opts[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onPick(idx);
                }
            });
        }

        GuardService s = GuardService.INSTANCE;
        if (s != null && s.getState() == GuardService.STATE_LOCKED) {
            showLocked();
        } else {
            showQuestion();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // This component is exclusively real enforcement. Test intents cannot change its mode.
        GuardService service = GuardService.INSTANCE;
        if (service != null && service.getState() == GuardService.STATE_LOCKED) showLocked();
        else if (lockedView) showQuestion();
        step();
    }

    @Override
    protected void onResume() {
        super.onResume();
        GuardService.challengeVisible = true;
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        GuardService.challengeVisible = false;
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        GuardService.challengeVisible = false;
        handler.removeCallbacks(ticker);
        super.onDestroy();
    }

    private void step() {
        GuardService s = GuardService.INSTANCE;
        if (s == null || s.getState() == GuardService.STATE_MONITORING) {
            finish();
            return;
        }
        if (!s.isTargetForeground()) {
            Log.i(TAG, "target not foreground, dismiss challenge");
            finish();
            return;
        }
        if (lockedView) {
            long left = s.getLockedUntil() - SystemClock.elapsedRealtime();
            if (left <= 0L) {
                s.unlockNow();
                showQuestion();
            } else {
                tvCountdown.setText("请等待 " + fmt(left));
            }
        }
    }

    private void showQuestion() {
        GuardService s = GuardService.INSTANCE;
        if (s != null && s.getState() == GuardService.STATE_LOCKED) {
            showLocked();
            return;
        }
        lockedView = false;
        question = Questions.random(Prefs.getType(this), Prefs.getDifficulty(this));
        tvTitle.setText("请完成验证 · " + question.kindLabel);
        tvTitle.setTextColor(getResources().getColor(R.color.guard_accent));
        tvQuestion.setVisibility(View.VISIBLE);
        tvQuestion.setText(question.prompt);
        tvCountdown.setVisibility(View.GONE);
        tvReveal.setVisibility(View.GONE);
        tvFooter.setText(question.hint);
        for (int i = 0; i < 4; i++) {
            opts[i].setText(question.options[i]);
            opts[i].setEnabled(true);
            opts[i].setVisibility(View.VISIBLE);
        }
        opts[0].requestFocus();
    }

    private void showLocked() {
        lockedView = true;
        GuardService s = GuardService.INSTANCE;
        tvTitle.setText(R.string.locked_title);
        tvTitle.setTextColor(getResources().getColor(R.color.guard_danger));
        if (question != null) {
            tvQuestion.setVisibility(View.VISIBLE);
        } else {
            tvQuestion.setVisibility(View.GONE);
        }
        tvCountdown.setVisibility(View.VISIBLE);
        tvFooter.setText(R.string.locked_sub);

        // 展示正确答案：优先用当前题，界面被重建时用服务里记下的答案
        String reveal = question != null ? question.reveal
                : (s != null ? s.getLastReveal() : "");
        if (reveal != null && reveal.length() > 0) {
            tvReveal.setText("正确答案：" + reveal);
            tvReveal.setVisibility(View.VISIBLE);
        } else {
            tvReveal.setVisibility(View.GONE);
        }

        for (int i = 0; i < 4; i++) {
            opts[i].setEnabled(false);
            opts[i].setText("—");
        }
        if (s != null) {
            long left = s.getLockedUntil() - SystemClock.elapsedRealtime();
            if (left < 0L) {
                left = 0L;
            }
            tvCountdown.setText("请等待 " + fmt(left));
        } else {
            tvCountdown.setText("");
        }
    }

    private void onPick(int idx) {
        if (lockedView || question == null) {
            return;
        }
        GuardService s = GuardService.INSTANCE;
        if (s == null || !s.isTargetForeground()
                || s.getState() == GuardService.STATE_MONITORING) {
            finish();
            return;
        }
        if (s.getState() == GuardService.STATE_LOCKED) {
            showLocked();
            return;
        }
        if (idx == question.answer) {
            Toast.makeText(this, R.string.correct_toast, Toast.LENGTH_SHORT).show();
            if (s != null) {
                s.onAnsweredCorrect();
            }
            finish();
        } else {
            Toast.makeText(this, R.string.wrong_toast, Toast.LENGTH_SHORT).show();
            if (s != null) {
                s.onAnsweredWrong(question.reveal);
                showLocked();
            } else {
                showQuestion();
            }
        }
    }

    private String fmt(long ms) {
        long total = (ms + 999L) / 1000L;
        long m = total / 60L;
        long sec = total % 60L;
        return String.format(Locale.CHINA, "%d 分 %02d 秒", m, sec);
    }

    @Override
    public void onBackPressed() {
        Toast.makeText(this, R.string.back_blocked, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
