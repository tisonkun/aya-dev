// Copyright (c) 2020-2023 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.cli.literate;

import com.intellij.openapi.util.text.StringUtil;
import kala.collection.Seq;
import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.MutableList;
import kala.text.StringSlice;
import org.aya.cli.utils.InlineHintProblem;
import org.aya.concrete.remark.AyaLiterate;
import org.aya.generic.AyaDocile;
import org.aya.literate.Literate;
import org.aya.literate.LiterateConsumer;
import org.aya.prettier.BasePrettier;
import org.aya.pretty.doc.Doc;
import org.aya.pretty.doc.Language;
import org.aya.util.error.SourcePos;
import org.aya.util.prettier.PrettierOptions;
import org.aya.util.reporter.Problem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * This prettier maintains all highlights created from {@link SyntaxHighlight} and all
 * problems reported by Aya compiler.
 * Implementation-wise, this prettier can be seen as a highlight server for a single file.
 * <p>
 * When the highlight of a code block is requested, it filters out
 * all highlights and problems that belong to the code block, and then
 * build a {@link Doc} containing the highlighted source code mixed with compiler
 * outputs, as done in {@link #highlight(String, SourcePos)}.
 *
 * @param problems   All problems of a single file
 * @param highlights All highlights of a single file
 */
public record FaithfulPrettier(
  @NotNull ImmutableSeq<Problem> problems,
  @NotNull ImmutableSeq<HighlightInfo> highlights,
  @NotNull PrettierOptions options
) implements LiterateConsumer {

  /**
   * Highlight all visible aya code blocks
   */
  @Override public void accept(@NotNull Literate literate) {
    if (literate instanceof AyaLiterate.AyaVisibleCodeBlock code && code.sourcePos != null) {
      code.highlighted = highlight(code.code, code.sourcePos);
    }
    LiterateConsumer.super.accept(literate);
  }

  private static void checkHighlights(@NotNull ImmutableSeq<HighlightInfo> highlights) {
    highlights.foldLeft(-1, (lastEndIndex, h) -> {
      var sp = h.sourcePos();
      if (!(sp.tokenStartIndex() <= sp.tokenEndIndex()))
        throw new IllegalArgumentException("Invalid source pos: " + sp);
      if (!(lastEndIndex < sp.tokenStartIndex()))
        throw new IllegalArgumentException("Intersect with previous source pos: " + sp);
      return sp.tokenEndIndex();
    });
  }

  /** find highlights and problems inside the code range, and merge them as new highlights */
  private static @NotNull ImmutableSeq<HighlightInfo> merge(
    @NotNull SourcePos codeRange,
    @NotNull PrettierOptions options,
    @NotNull ImmutableSeq<HighlightInfo> highlights,
    @NotNull ImmutableSeq<Problem> problems
  ) {
    var highlightInRange = highlights.view()
      .filter(h -> h.sourcePos() != SourcePos.NONE)
      .filterNot(h -> h.sourcePos().isEmpty())
      .filter(x -> codeRange.containsIndex(x.sourcePos()))
      .sorted().distinct()
      .toImmutableSeq();
    checkHighlights(highlightInRange);

    var problemsInRange = problems.view()
      .filter(p -> codeRange.containsIndex(p.sourcePos()))
      .flatMap(p -> InlineHintProblem.withInlineHints(p, options))
      .distinct()
      .toImmutableSeq();

    return problemsInRange.foldLeft(highlightInRange, (acc, p) -> {
      var partition = acc.partition(
        h -> p.sourcePos().containsIndex(h.sourcePos()));
      var inP = partition.component1().sorted();
      var wrap = new HighlightInfo.Err(p, inP);
      return partition.component2().appended(wrap);
    });
  }

  /**
   * Apply highlights to source code string.
   *
   * @param raw       the source code
   * @param codeRange where the raw start from (the 'raw' might be a piece of the source code,
   *                  so it probably not starts from 0).
   */
  public @NotNull Doc highlight(@NotNull String raw, @NotNull SourcePos codeRange) {
    var merged = merge(codeRange, options, highlights, problems).sorted();
    checkHighlights(merged);
    return doHighlight(StringSlice.of(raw), codeRange.tokenStartIndex(), merged);
  }

  private @NotNull Doc doHighlight(@NotNull StringSlice raw, int base, @NotNull ImmutableSeq<HighlightInfo> highlights) {
    var docs = MutableList.<Doc>create();

    for (var current : highlights) {
      // Cut the `raw` text at `base` offset into three parts: before, current, and remaining,
      // which needs two split positions: `current.sourcePos().start` and `current.sourcePos().end`, respectively.
      var knifeCut = twoKnifeThreeParts(raw, base, current.sourcePos());

      // If there's an orphan text before the highlighted cut, add it to the result as plain text.
      if (!knifeCut.before.isEmpty()) {
        docs.append(Doc.plain(knifeCut.before.toString()));
      }
      // `Doc.empty` is the unit of `Doc.cat`, so it is safe to add it to the result.
      var highlight = highlightOne(knifeCut.current.toString(), base, current);
      docs.append(highlight);

      // Move forward
      raw = knifeCut.remaining;
      base = knifeCut.base;
    }

    if (!raw.isEmpty()) docs.append(Doc.plain(raw.toString()));

    return Doc.cat(docs);
  }

  private @NotNull Doc highlightOne(@NotNull String raw, int base, @NotNull HighlightInfo highlight) {
    if (raw.isEmpty()) return Doc.empty();
    return switch (highlight) {
      case HighlightInfo.Def def -> Doc.linkDef(highlightVar(raw, def.kind()), def.target(), hover(def.type()));
      case HighlightInfo.Ref ref -> Doc.linkRef(highlightVar(raw, ref.kind()), ref.target(), hover(ref.type()));
      case HighlightInfo.Lit lit -> highlightLit(raw, lit.kind());
      case HighlightInfo.Err err -> {
        var doc = doHighlight(StringSlice.of(raw), base, err.children());
        var style = switch (err.problem().level()) {
          case ERROR -> BasePrettier.ERROR;
          case WARN -> BasePrettier.WARNING;
          case GOAL -> BasePrettier.GOAL;
          case INFO -> null;
        };
        yield style == null ? doc : new Doc.Tooltip(Doc.styled(style, doc), () -> Doc.codeBlock(
          Language.Builtin.Aya,
          err.problem().brief(options).toDoc()
        ));
      }
    };
  }

  private @Nullable String hover(@Nullable AyaDocile term) {
    if (term == null) return null;
    return term.toDoc(options()).commonRender();
  }

  private @NotNull Doc highlightVar(@NotNull String raw, @NotNull HighlightInfo.DefKind defKind) {
    var style = switch (defKind) {
      case Data -> BasePrettier.DATA;
      case Con -> BasePrettier.CON;
      case Clazz -> BasePrettier.CLAZZ;
      case Member -> BasePrettier.MEMBER;
      case Fn -> BasePrettier.FN;
      case Prim -> BasePrettier.PRIM;
      case Generalized -> BasePrettier.GENERALIZED;
      case LocalVar -> BasePrettier.LOCAL_VAR;
      case Unknown, Module -> null;
    };
    return style != null ? Doc.styled(style, raw) : Doc.plain(raw);
  }

  private @NotNull Doc highlightLit(@NotNull String raw, @NotNull HighlightInfo.LitKind litKind) {
    return switch (litKind) {
      case Int, Whitespace -> Doc.plain(raw);
      case String -> Doc.plain(StringUtil.escapeStringCharacters(raw));
      case Keyword -> Doc.styled(BasePrettier.KEYWORD, Doc.symbol(raw));
      case Comment -> Doc.styled(BasePrettier.COMMENT, raw);
      case SpecialSymbol -> Doc.symbol(raw);
      case Eol -> Doc.cat(Seq.fill(raw.length(), Doc.line()));
    };
  }

  private static @NotNull KnifeCut twoKnifeThreeParts(@NotNull StringSlice raw, int base, @NotNull SourcePos twoKnife) {
    var beginPart1 = twoKnife.tokenStartIndex() - base;
    var endPart1 = twoKnife.tokenEndIndex() + 1 - base;
    var part0 = raw.subSequence(0, beginPart1);
    var part1 = raw.subSequence(beginPart1, endPart1);
    var part2 = raw.subSequence(endPart1, raw.length());
    return new KnifeCut(part0, part1, part2, twoKnife.tokenEndIndex() + 1);
  }

  record KnifeCut(
    @NotNull StringSlice before,
    @NotNull StringSlice current,
    @NotNull StringSlice remaining,
    int base
  ) {
  }
}
