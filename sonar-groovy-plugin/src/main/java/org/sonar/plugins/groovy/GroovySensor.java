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
package org.sonar.plugins.groovy;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import groovyjarjarantlr4.runtime.Token;
import groovyjarjarantlr4.v4.runtime.CommonToken;
import org.apache.commons.io.IOUtils;
import org.apache.groovy.parser.antlr4.GroovyLangLexer;
import org.apache.groovy.parser.antlr4.GroovyLexer;
import org.gmetrics.result.MetricResult;
import org.gmetrics.result.NumberMetricResult;
import org.gmetrics.resultsnode.ClassResultsNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.PropertyType;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputComponent;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.measure.Metric;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.config.Configuration;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.FileLinesContext;
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.plugins.groovy.foundation.Groovy;
import org.sonar.plugins.groovy.foundation.GroovyFileSystem;
import org.sonar.plugins.groovy.foundation.GroovyHighlighterAndTokenizer;
import org.sonar.plugins.groovy.gmetrics.GMetricsSourceAnalyzer;

public class GroovySensor implements Sensor {

  static final String IGNORE_HEADER_COMMENTS = "sonar.groovy.ignoreHeaderComments";

  private static final Logger LOG = LoggerFactory.getLogger(GroovySensor.class);

  private static final String CYCLOMATIC_COMPLEXITY_METRIC_NAME = "CyclomaticComplexity";

  private static final Set<String> EMPTY_COMMENT_LINES =
      Arrays.stream(new String[] {"/**", "/*", "*", "*/", "//"}).collect(Collectors.toSet());

  private final Configuration settings;
  private final FileLinesContextFactory fileLinesContextFactory;
  private final GroovyFileSystem groovyFileSystem;

  private int loc = 0;
  private int comments = 0;
  private int currentLine = 0;
  private FileLinesContext fileLinesContext;

  public GroovySensor(
      Configuration settings,
      FileLinesContextFactory fileLinesContextFactory,
      FileSystem fileSystem) {
    this.settings = settings;
    this.fileLinesContextFactory = fileLinesContextFactory;
    this.groovyFileSystem = new GroovyFileSystem(fileSystem);
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor.onlyOnLanguage(Groovy.KEY).name(this.toString());
  }

  @Override
  public void execute(SensorContext context) {
    if (groovyFileSystem.hasGroovyFiles()) {
      List<InputFile> inputFiles = groovyFileSystem.sourceInputFiles();
      computeBaseMetrics(context, inputFiles);
      computeGroovyMetrics(context, inputFiles);
        try {
            highlightFiles(context, groovyFileSystem.groovyInputFiles());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
  }

  private static void computeGroovyMetrics(SensorContext context, List<InputFile> inputFiles) {
    GMetricsSourceAnalyzer metricsAnalyzer =
        new GMetricsSourceAnalyzer(context.fileSystem(), inputFiles);

    metricsAnalyzer.analyze();

    for (Entry<InputFile, List<ClassResultsNode>> entry :
        metricsAnalyzer.resultsByFile().entrySet()) {
      processFile(context, entry.getKey(), entry.getValue());
    }
  }

  private static void processFile(
      SensorContext context, InputFile sonarFile, Collection<ClassResultsNode> results) {
    int classes = 0;
    int methods = 0;
    int complexity = 0;

    for (ClassResultsNode result : results) {
      classes += 1;

      methods += result.getChildren().size();

      Optional<MetricResult> cyclomaticComplexity =
          getCyclomaticComplexity(result.getMetricResults());
      if (cyclomaticComplexity.isPresent()) {
        int value =
            (Integer) ((NumberMetricResult) cyclomaticComplexity.get()).getValues().get("total");
        complexity += value;
      }
    }

    saveMetric(context, sonarFile, CoreMetrics.CLASSES, classes);
    saveMetric(context, sonarFile, CoreMetrics.FUNCTIONS, methods);
    saveMetric(context, sonarFile, CoreMetrics.COMPLEXITY, complexity);
  }

  private static Optional<MetricResult> getCyclomaticComplexity(List<MetricResult> metricResults) {
    return metricResults.stream()
        .filter(
            metricResult ->
                CYCLOMATIC_COMPLEXITY_METRIC_NAME.equals(metricResult.getMetric().getName()))
        .findAny();
  }

  private void computeBaseMetrics(SensorContext context, List<InputFile> inputFiles) {
    for (InputFile groovyFile : inputFiles) {
      computeBaseMetrics(context, groovyFile);
    }
  }

  private void computeBaseMetrics(SensorContext context, InputFile groovyFile) {
    loc = 0;
    comments = 0;
    currentLine = 0;
    fileLinesContext = fileLinesContextFactory.createFor(groovyFile);
    try (InputStreamReader streamReader =
        new InputStreamReader(groovyFile.inputStream(), groovyFile.charset())) {
      List<String> lines = IOUtils.readLines(groovyFile.inputStream(), groovyFile.charset());
      GroovyLexer groovyLexer = new GroovyLangLexer(streamReader);
      CommonToken token = (CommonToken) groovyLexer.nextToken();
      CommonToken nextToken = (CommonToken) groovyLexer.nextToken();
      while (nextToken.getType() != Token.EOF) {
        handleToken(token, nextToken.getLine(), lines);
        token = nextToken;
        nextToken = (CommonToken) groovyLexer.nextToken();
      }
      handleToken(token, nextToken.getLine(), lines);
      saveMetric(context, groovyFile, CoreMetrics.NCLOC, loc);
      saveMetric(context, groovyFile, CoreMetrics.COMMENT_LINES, comments);
    } catch (IOException e) {
      LOG.error("Unable to read file: {}", groovyFile, e);
    }
    fileLinesContext.save();
  }

  private static void highlightFiles(SensorContext context, List<InputFile> inputFiles) throws IOException {
    for (InputFile inputFile : inputFiles) {
      new GroovyHighlighterAndTokenizer(inputFile).processFile(context);
    }
  }

  private static <T extends Serializable> void saveMetric(
      SensorContext context, InputComponent inputComponent, Metric<T> metric, T value) {
    context.<T>newMeasure().withValue(value).forMetric(metric).on(inputComponent).save();
  }

  private void handleToken(CommonToken token, int nextTokenLine, List<String> lines) {
    int tokenType = token.getType();
    int tokenLine = token.getLine();
    if (isComment(tokenType)) {
      if (isNotHeaderComment(tokenLine)) {
        comments += nextTokenLine - tokenLine + 1 - numberEmptyLines(token, lines);
      }
    } else if (isNotWhitespace(tokenType) && tokenLine != currentLine) {
      loc++;
      fileLinesContext.setIntValue(CoreMetrics.NCLOC_DATA_KEY, tokenLine, 1);
      currentLine = tokenLine;
    }
  }

  private int numberEmptyLines(CommonToken token, List<String> lines) {
    List<String> relatedLines = getLinesFromToken(lines, token);
    long emptyLines =
        relatedLines.stream().map(String::trim).filter(EMPTY_COMMENT_LINES::contains).count();
    return (int) emptyLines;
  }

  private static List<String> getLinesFromToken(List<String> lines, CommonToken gst) {
    List<String> newLines = new ArrayList<>(lines.subList(gst.getLine() - 1, gst.getLine()));

    int lastLineIndex = newLines.size() - 1;
    String lastLine =
        newLines
            .get(lastLineIndex)
            .substring(0, gst.getCharPositionInLine() + gst.toString().length() - 1);
    newLines.set(lastLineIndex, lastLine);

    String firstLine = newLines.get(0).substring(gst.getCharPositionInLine() - 1);
    newLines.set(0, firstLine);

    return newLines;
  }

  private boolean isNotHeaderComment(int tokenLine) {
    return !(tokenLine == 1 && settings.getBoolean(IGNORE_HEADER_COMMENTS).orElse(true));
  }

  private static boolean isNotWhitespace(int tokenType) {
    return (tokenType != GroovyLexer.WS);
  }

  private static boolean isComment(int tokenType) {
    return tokenType == GroovyLexer.SH_COMMENT;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName();
  }

  public static List<Object> getExtensions() {
    return Arrays.asList(
        GroovySensor.class,
        GroovySonarWayProfile.class,
        PropertyDefinition.builder(IGNORE_HEADER_COMMENTS)
            .name("Ignore Header Comments")
            .description(
                "If set to \"true\", the file headers (that are usually the same on each file: licensing information for example) are not considered as comments. "
                    + "Thus metrics such as \"Comment lines\" do not get incremented. "
                    + "If set to \"false\", those file headers are considered as comments and metrics such as \"Comment lines\" get incremented.")
            .category(Groovy.NAME)
            .subCategory("Base")
            .defaultValue("true")
            .type(PropertyType.BOOLEAN)
            .build());
  }
}
