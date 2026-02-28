package lld.designpatterns.abstractfactory;

public final class LightThemeFactory implements UIFactory {

    private static final String THEME = "Light";

    @Override
    public Button createButton(String label) {
        return new LightButton(label);
    }

    @Override
    public TextBox createTextBox(String placeholder) {
        return new LightTextBox(placeholder);
    }

    @Override
    public Checkbox createCheckbox(String label) {
        return new LightCheckbox(label);
    }

    @Override
    public String themeName() {
        return THEME;
    }

    private static final class LightButton implements Button {
        private final String label;
        LightButton(String label) { this.label = label; }
        @Override public String render() { return "[Light Button: " + label + "]"; }
        @Override public String themeName() { return THEME; }
    }

    private static final class LightTextBox implements TextBox {
        private final String placeholder;
        LightTextBox(String placeholder) { this.placeholder = placeholder; }
        @Override public String render() { return "<Light TextBox placeholder=\"" + placeholder + "\">"; }
        @Override public String themeName() { return THEME; }
    }

    private static final class LightCheckbox implements Checkbox {
        private final String label;
        LightCheckbox(String label) { this.label = label; }
        @Override public String render() { return "[ ] Light: " + label; }
        @Override public String themeName() { return THEME; }
    }
}
