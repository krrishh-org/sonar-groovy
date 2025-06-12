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
package org.sonar.plugins.groovy.foundation;

import groovyjarjarantlr4.v4.runtime.RecognitionException;
import groovyjarjarantlr4.v4.runtime.Token;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.commons.lang.StringUtils;
import org.apache.groovy.parser.antlr4.GroovyLangLexer;
import org.apache.groovy.parser.antlr4.GroovyLexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.cpd.NewCpdTokens;
import org.sonar.api.batch.sensor.highlighting.NewHighlighting;
import org.sonar.api.batch.sensor.highlighting.TypeOfText;

public class GroovyHighlighterAndTokenizer {

  private static final Logger LOG = LoggerFactory.getLogger(GroovyHighlighterAndTokenizer.class);

  private static final int[] KEYWORDS = {
    GroovyLexer.AS,
    GroovyLexer.ASSERT,
    GroovyLexer.BooleanLiteral,
    GroovyLexer.BREAK,
    GroovyLexer.CASE,
    GroovyLexer.CATCH,
    GroovyLexer.CLASS,
    GroovyLexer.CONTINUE,
    GroovyLexer.DEF,
    GroovyLexer.DEFAULT,
    GroovyLexer.ELSE,
    GroovyLexer.ENUM,
    GroovyLexer.EXTENDS,
    GroovyLexer.FINALLY,
    GroovyLexer.FloatingPointLiteral,
    GroovyLexer.FOR,
    GroovyLexer.IF,
    GroovyLexer.IMPLEMENTS,
    GroovyLexer.IMPORT,
    GroovyLexer.IN,
    GroovyLexer.INSTANCEOF,
    GroovyLexer.IntegerLiteral,
    GroovyLexer.INTERFACE,
    GroovyLexer.NATIVE,
    GroovyLexer.NEW,
    GroovyLexer.NullLiteral,
    GroovyLexer.PACKAGE,
    GroovyLexer.PRIVATE,
    GroovyLexer.PROTECTED,
    GroovyLexer.PUBLIC,
    GroovyLexer.RETURN,
    GroovyLexer.STATIC,
    GroovyLexer.StringLiteral,
    GroovyLexer.SUPER,
    GroovyLexer.SWITCH,
    GroovyLexer.SYNCHRONIZED,
    GroovyLexer.THIS,
    GroovyLexer.THREADSAFE,
    GroovyLexer.THROW,
    GroovyLexer.THROWS,
    GroovyLexer.TRAIT,
    GroovyLexer.TRANSIENT,
    GroovyLexer.TRY,
    GroovyLexer.VOID,
    GroovyLexer.VOLATILE,
    GroovyLexer.WHILE
  };

  private static final int[] STRINGS = {
    GroovyLexer.GStringBegin,
    GroovyLexer.GStringEnd,
    GroovyLexer.GStringPart,
    GroovyLexer.GStringPathPart,
    GroovyLexer.DQ_GSTRING_MODE,
    GroovyLexer.SLASHY_GSTRING_MODE,
    GroovyLexer.TDQ_GSTRING_MODE,
    GroovyLexer.REGEX_MATCH,
    GroovyLexer.REGEX_FIND,
    GroovyLexer.DOT,
    GroovyLexer.DOLLAR_SLASHY_GSTRING_MODE,
    GroovyLexer.GSTRING_PATH_MODE,
    GroovyLexer.GSTRING_TYPE_SELECTOR_MODE,
    GroovyLexer.SAFE_DOT,
    GroovyLexer.SPREAD_DOT,
    GroovyLexer.SAFE_CHAIN_DOT
  };

  private static final int[] CONSTANTS = {};

  private static final int[] COMMENTS = {GroovyLexer.SH_COMMENT};

  private static final List<TypeOfTextToTokenTypes> HIGHLIGHTING_MAPPING =
      Arrays.asList(
          new TypeOfTextToTokenTypes(TypeOfText.KEYWORD, KEYWORDS),
          new TypeOfTextToTokenTypes(TypeOfText.STRING, STRINGS),
          new TypeOfTextToTokenTypes(TypeOfText.CONSTANT, CONSTANTS),
          new TypeOfTextToTokenTypes(TypeOfText.COMMENT, COMMENTS));

  private final InputFile inputFile;
  private final InputStream inputStream;
  private boolean isAnnotation;

  public GroovyHighlighterAndTokenizer(InputFile inputFile) throws IOException {
    this.inputFile = inputFile;
    this.inputStream = inputFile.inputStream();
  }

  public void processFile(SensorContext context) {
    List<GroovyToken> tokens = new ArrayList<>();
    isAnnotation = false;

    try (InputStreamReader streamReader = new InputStreamReader(inputStream)) {

      GroovyLexer groovyLexer = new GroovyLangLexer(streamReader);

      Token token = groovyLexer.nextToken();

      int type = token.getType();
      while (type != Token.EOF) {
        String text = token.getText();
        TypeOfText typeOfText = typeOfText(type, text).orElse(null);
        int lines = StringUtils.countMatches(text, "\n");
        String lastLine = lines == 0 ? text : text.substring(text.lastIndexOf("\n"));
        if (StringUtils.isNotBlank(text)) {
          GroovyToken gt = new GroovyToken(
                          token.getLine(),
                          token.getCharPositionInLine(),
                          token.getLine() + lines,
                          lines == 0 ? token.getCharPositionInLine() + text.length() : lastLine.length()-1,
                          getImage(token, text),
                          typeOfText);
          tokens.add(gt);
        }
        token = groovyLexer.nextToken();
        type = token.getType();
      }
    } catch (RecognitionException e) {
      LOG.error("Unexpected token when lexing file: " + inputFile.filename(), e);
    } catch (IOException e) {
      LOG.error("Unable to read file: " + inputFile.filename(), e);
    }

    if (!tokens.isEmpty()) {
      boolean isNotTest = inputFile.type() != InputFile.Type.TEST;
      NewCpdTokens cpdTokens = isNotTest ? context.newCpdTokens().onFile(inputFile) : null;
      NewHighlighting highlighting = context.newHighlighting().onFile(inputFile);
      for (GroovyToken groovyToken : tokens) {
        if (isNotTest) {
          cpdTokens =
              cpdTokens.addToken(
                  groovyToken.startLine,
                  groovyToken.startLineOffset,
                  groovyToken.endLine,
                  groovyToken.endLineOffset,
                  groovyToken.image);
        }
        if (groovyToken.typeOfText != null) {
          highlighting =
              highlighting.highlight(
                  groovyToken.startLine,
                  groovyToken.startLineOffset,
                  groovyToken.endLine,
                  groovyToken.endLineOffset,
                  groovyToken.typeOfText);
        }
      }
      highlighting.save();
      if (isNotTest) {
        cpdTokens.save();
      }
    }
  }

  private String getImage(Token token, String text) {
    if (token.getType() == GroovyLexer.StringLiteral) {
      return "LITERAL";
    }
    return text;
  }

  private Optional<TypeOfText> typeOfText(int type, String text) {
    TypeOfText result = null;
    for (TypeOfTextToTokenTypes mapping : HIGHLIGHTING_MAPPING) {
      if (Arrays.stream(mapping.tokenTypes).anyMatch(tokenType -> tokenType == type)) {
        result = mapping.typeOfText;
        break;
      }
    }

    if (result == TypeOfText.COMMENT && text.startsWith("/**")) {
      result = TypeOfText.STRUCTURED_COMMENT;
    } else if (result == null && (type == GroovyLexer.AT || isAnnotation)) {
      isAnnotation = isPartOfAnnotation(type);
      result = isAnnotation ? TypeOfText.ANNOTATION : null;
    }

    return Optional.ofNullable(result);
  }

  private static boolean isPartOfAnnotation(int type) {
    return type == GroovyLexer.AT || type == GroovyLexer.Identifier || type == GroovyLexer.DOT;
  }

  private static class GroovyToken {
    final int startLine;
    final int startLineOffset;
    final int endLine;
    final int endLineOffset;
    final String image;
    @Nullable final TypeOfText typeOfText;

    public GroovyToken(
        int startLine,
        int startLineOffset,
        int endLine,
        int endLineOffset,
        String image,
        @Nullable TypeOfText typeOfText) {
      this.startLine = startLine;
      this.startLineOffset = startLineOffset;
      this.endLine = endLine;
      this.endLineOffset = endLineOffset;
      this.image = image;
      this.typeOfText = typeOfText;
    }
  }

  private static class TypeOfTextToTokenTypes {
    final int[] tokenTypes;
    final TypeOfText typeOfText;

    public TypeOfTextToTokenTypes(TypeOfText typeOfText, int[] tokenTypes) {
      this.tokenTypes = tokenTypes;
      this.typeOfText = typeOfText;
    }
  }
}
