package lld.designpatterns.abstractfactory;

public final class DarkThemeFactory implements UIFactory {

    private static final String THEME = "Dark";

    @Override
    public Button createButton(String label) {
        return new DarkButton(label);
    }

    @Override
    public TextBox createTextBox(String placeholder) {
        return new DarkTextBox(placeholder);
    }

    @Override
    public Checkbox createCheckbox(String label) {
        return new DarkCheckbox(label);
    }

    @Override
    public String themeName() {
        return THEME;
    }

    private static final class DarkButton implements Button {
        private final String label;
        DarkButton(String label) { this.label = label; }
        @Override public String render() { return "[Dark Button: " + label + "]"; }
        @Override public String themeName() { return THEME; }
    }

    private static final class DarkTextBox implements TextBox {
        private final String placeholder;
        DarkTextBox(String placeholder) { this.placeholder = placeholder; }
        @Override public String render() { return "<Dark TextBox placeholder=\"" + placeholder + "\">"; }
        @Override public String themeName() { return THEME; }
    }

    private static final class DarkCheckbox implements Checkbox {
        private final String label;
        DarkCheckbox(String label) { this.label = label; }
        @Override public String render() { return "[ ] Dark: " + label; }
        @Override public String themeName() { return THEME; }
    }
}
