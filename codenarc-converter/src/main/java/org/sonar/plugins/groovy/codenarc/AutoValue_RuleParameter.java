package org.sonar.plugins.groovy.codenarc;

public class AutoValue_RuleParameter extends RuleParameter {
    private final String key;
    private final String description;
    private final String defaultValue;

    public AutoValue_RuleParameter(String key, String description, String defaultValue) {
        super();
        this.key = key;
        this.description = description;
        this.defaultValue = defaultValue;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String defaultValue() {
        return defaultValue;
    }
}
