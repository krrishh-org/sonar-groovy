/*
 * Sonar Groovy Plugin
 * Copyright (C) 2010-2025 SonarQube Community
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
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
