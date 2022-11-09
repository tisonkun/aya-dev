// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.cli.repl;

import com.google.gson.*;
import kala.collection.immutable.ImmutableMap;
import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.MutableMap;
import kala.control.Result;
import org.aya.pretty.printer.ColorScheme;
import org.aya.pretty.printer.StyleFamily;
import org.aya.pretty.style.AyaColorScheme;
import org.aya.util.reporter.Problem;
import org.aya.util.reporter.Reporter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Objects;

public record RenderOptions(
  @NotNull ColorScheme colorScheme,
  @NotNull StyleFamily styleFamily
) {
  public final static ImmutableMap<String, ColorScheme> BUILTIN_COLOR_SCHEMES = ImmutableMap.of(
    "emacs", AyaColorScheme.EMACS,
    "intellij", AyaColorScheme.INTELLIJ
  );

  public final static class Deserializer implements JsonDeserializer<RenderOptions>, JsonSerializer<RenderOptions> {
    public static class ColorSchemeJson {
      @Nullable String name;
      @Nullable java.util.Map<String, String> override;

      public ColorSchemeJson() {
      }

      public ColorSchemeJson(@Nullable String name, @Nullable java.util.Map<String, String> override) {
        this.name = name;
        this.override = override;
      }
    }

    public static class RenderOptionsJson {
      @Nullable ColorSchemeJson colorScheme;
      @Nullable JsonElement styleFamily;    // TODO

      public RenderOptionsJson() {
      }

      public RenderOptionsJson(@Nullable ColorSchemeJson colorScheme, @Nullable JsonElement styleFamily) {
        this.colorScheme = colorScheme;
        this.styleFamily = styleFamily;
      }
    }

    public @NotNull Reporter reporter;
    public @NotNull RenderOptions fallback;

    public Deserializer(@NotNull Reporter reporter, @NotNull RenderOptions fallback) {
      this.reporter = reporter;
      this.fallback = fallback;
    }

    public @NotNull ColorScheme colorSchemeFromJson(@NotNull ColorSchemeJson options) {
      var name = Objects.toString(options.name);      // "null" if null
      var builtin = BUILTIN_COLOR_SCHEMES.getOrElse(name.toLowerCase(), () -> {
        reporter.reportString("\"" + name + "\" is not a valid color scheme name",
          Problem.Severity.WARN);   // TODO: Problem instead of String

        return fallback.colorScheme();
      });

      var builder = MutableMap.from(builtin.definedColors());
      var validKeys = ImmutableSeq.from(AyaColorScheme.Key.values()).map(AyaColorScheme.Key::key);

      var override = options.override == null
        ? Map.<String, String>of()
        : options.override;

      for (var pair : override.entrySet()) {
        var key = pair.getKey();
        var value = pair.getValue();

        var isValidKey = key != null && validKeys.contains(key);
        var isValidValue = value != null;

        if (!isValidKey) {
          reporter.reportString("Invalid key: " + key, Problem.Severity.WARN);
          continue;
        }

        if (!isValidValue) {
          reporter.reportString("Invalid value: " + value, Problem.Severity.WARN);
          continue;
        }

        var parsedColor = parseColor(value);

        if (parsedColor.isErr()) {
          reporter.reportString(parsedColor.getErr(), Problem.Severity.WARN);
          continue;
        }

        builder.put(key, parsedColor.get());
      }

      return new AyaColorScheme(builder);
    }

    public static @NotNull Result<Integer, String> parseColor(@NotNull String color) {
      String colorCode;
      if (color.charAt(0) == '#') colorCode = color.substring(1);
      else if (color.startsWith("0x")) colorCode = color.substring(2);
      else colorCode = color;

      if (colorCode.length() != 6) {
        return Result.err("The color code \"" + color + "\" is too long or too short!");
      }

      try {
        var result = Integer.parseInt(colorCode, 16);

        return Result.ok(result);
      } catch (NumberFormatException e) {
        return Result.err(e.getMessage());
      }
    }

    public @NotNull StyleFamily styleFamilyFromJson(@NotNull JsonElement json) {
      // TODO
      return fallback.styleFamily();
    }

    @Override
    public RenderOptions deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
      assert json != null;
      assert context != null;

      var jsonObj = (RenderOptionsJson) context.deserialize(json, RenderOptionsJson.class);

      ColorScheme colorScheme;
      StyleFamily styleFamily;

      if (jsonObj.colorScheme == null) {
        reporter.reportString("'colorScheme' is null");
        colorScheme = fallback.colorScheme();
      } else {
        colorScheme = colorSchemeFromJson(jsonObj.colorScheme);
      }

      if (jsonObj.styleFamily == null) {
        reporter.reportString("'styleFamily' is null");
        styleFamily = fallback.styleFamily();
      } else {
        styleFamily = styleFamilyFromJson(jsonObj.styleFamily);
      }

      return new RenderOptions(colorScheme, styleFamily);
    }

    @Override
    public JsonElement serialize(RenderOptions src, Type typeOfSrc, JsonSerializationContext context) {
      assert src != null;

      // serialize to:
      // "colorScheme": { "name": null, "override": colorScheme.definedColor() }

      var colorScheme = new JsonObject();
      var override = new JsonObject();

      src.colorScheme().definedColors().forEach((k, v) -> {
        override.add(k, new JsonPrimitive(String.format("#%06X", v)));
      });

      colorScheme.add("name", JsonNull.INSTANCE);
      colorScheme.add("override", override);

      // TODO: styleFamily

      var wholeObj = new JsonObject();
      wholeObj.add("colorScheme", colorScheme);

      return wholeObj;
    }
  }
}
