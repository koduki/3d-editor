package dev.koduki.kucode.probe;
public class MainActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.widget.TextView text = new android.widget.TextView(this);
        text.setText(R.string.app_name);
        setContentView(text);
    }
    static int add(int a, int b) { return a + b; }
}
