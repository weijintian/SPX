package com.spx.chat;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.view.Gravity;
/** Only a synthetic pattern for generating a local video fixture. */
public final class MediaFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);TextView view=new TextView(this);view.setText("SPX\nMEDIA TEST");view.setGravity(Gravity.CENTER);view.setTextSize(32);view.setTextColor(0xffffffff);view.setBackgroundColor(0xff267865);setContentView(view);}
}
