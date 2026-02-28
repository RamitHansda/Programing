package lld.designpatterns.abstractfactory;

/**
 * Abstract Factory: creates a family of widgets for a theme. Clients use this
 * and get consistent Button + TextBox + Checkbox (e.g. all Light or all Dark).
 */
public interface UIFactory {
    Button createButton(String label);
    TextBox createTextBox(String placeholder);
    Checkbox createCheckbox(String label);
    String themeName();
}
