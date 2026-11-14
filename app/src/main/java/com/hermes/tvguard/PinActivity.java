package com.hermes.tvguard;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 密码键盘：遥控器可操作，用于「设置密码 / 修改密码 / 退出前校验」。
 * 输入满 4 位自动提交。
 */
public class PinActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    /** 校验密码（退出守护前用）。 */
    public static final int MODE_VERIFY = 0;
    /** 首次设置密码。 */
    public static final int MODE_SET = 1;
    /** 修改密码（先验旧密码）。 */
    public static final int MODE_CHANGE = 2;

    private static final int LEN = 4;
    private static final int MAX_FAILS = 5;

    private int mode = MODE_VERIFY;
    /** 多步流程的当前步骤。 */
    private int step = 0;
    private String input = "";
    /** 第一次输入的新密码，用于二次确认。 */
    private String first = "";
    private int fails = 0;

    private TextView tvTitle;
    private TextView tvTip;
    private TextView tvDots;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_VERIFY);
        if (mode == MODE_CHANGE && !PinStore.hasPin(this)) {
            mode = MODE_SET;
        }
        setContentView(R.layout.activity_pin);

        tvTitle = findViewById(R.id.pin_title);
        tvTip = findViewById(R.id.pin_tip);
        tvDots = findViewById(R.id.pin_dots);

        int[] ids = {R.id.k0, R.id.k1, R.id.k2, R.id.k3, R.id.k4,
                R.id.k5, R.id.k6, R.id.k7, R.id.k8, R.id.k9};
        for (int i = 0; i < ids.length; i++) {
            final String d = String.valueOf(i);
            Button b = findViewById(ids[i]);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onDigit(d);
                }
            });
        }
        findViewById(R.id.k_del).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (input.length() > 0) {
                    input = input.substring(0, input.length() - 1);
                    render();
                }
            }
        });
        findViewById(R.id.k_ok).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit();
            }
        });

        updateUi();
        findViewById(R.id.k5).requestFocus();
    }

    private void onDigit(String d) {
        if (input.length() >= LEN) {
            return;
        }
        input = input + d;
        render();
        if (input.length() == LEN) {
            submit();
        }
    }

    private void render() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LEN; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(i < input.length() ? '●' : '○');
        }
        tvDots.setText(sb.toString());
    }

    private void updateUi() {
        if (mode == MODE_VERIFY) {
            tvTitle.setText("退出守护");
            tvTip.setText("请输入密码后再退出");
        } else if (mode == MODE_SET) {
            tvTitle.setText("设置密码");
            tvTip.setText(step == 0 ? "请设置 4 位数字密码" : "请再次输入以确认");
        } else {
            tvTitle.setText("修改密码");
            if (step == 0) {
                tvTip.setText("请输入当前密码");
            } else if (step == 1) {
                tvTip.setText("请输入新密码");
            } else {
                tvTip.setText("请再次输入新密码");
            }
        }
        render();
    }

    private void submit() {
        if (input.length() != LEN) {
            Toast.makeText(this, "请输入 4 位数字", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mode == MODE_VERIFY) {
            if (PinStore.check(this, input)) {
                setResult(RESULT_OK);
                finish();
            } else {
                fail();
            }
            return;
        }
        if (mode == MODE_SET) {
            if (step == 0) {
                first = input;
                input = "";
                step = 1;
                updateUi();
            } else if (input.equals(first)) {
                PinStore.setPin(this, input);
                Toast.makeText(this, "密码已设置", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            } else {
                Toast.makeText(this, "两次输入不一致，请重新设置", Toast.LENGTH_SHORT).show();
                first = "";
                input = "";
                step = 0;
                updateUi();
            }
            return;
        }
        // MODE_CHANGE
        if (step == 0) {
            if (PinStore.check(this, input)) {
                input = "";
                step = 1;
                updateUi();
            } else {
                fail();
            }
        } else if (step == 1) {
            first = input;
            input = "";
            step = 2;
            updateUi();
        } else if (input.equals(first)) {
            PinStore.setPin(this, input);
            Toast.makeText(this, "密码已修改", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } else {
            Toast.makeText(this, "两次输入不一致，请重新输入新密码", Toast.LENGTH_SHORT).show();
            first = "";
            input = "";
            step = 1;
            updateUi();
        }
    }

    private void fail() {
        fails++;
        input = "";
        render();
        if (fails >= MAX_FAILS) {
            Toast.makeText(this, "密码错误次数过多，已退出", Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        Toast.makeText(this, "密码错误，还可尝试 " + (MAX_FAILS - fails) + " 次",
                Toast.LENGTH_SHORT).show();
    }
}
