package lld.designpatterns.abstractfactory;

/**
 * Abstract Factory: common interface for UI widgets. Themes supply consistent families.
 */
public interface Widget {
    String render();
    String themeName();
}
