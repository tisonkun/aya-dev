// Copyright (c) 2020-2023 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.literate;

import kala.collection.immutable.ImmutableSeq;
import kala.control.Option;
import org.aya.cli.literate.FaithfulPrettier;
import org.aya.cli.literate.SyntaxHighlight;
import org.aya.cli.parse.AyaParserImpl;
import org.aya.prettier.AyaPrettierOptions;
import org.aya.pretty.doc.Doc;
import org.aya.pretty.doc.Language;
import org.aya.resolve.context.EmptyContext;
import org.aya.test.AyaThrowingReporter;
import org.aya.tyck.TyckDeclTest;
import org.aya.util.error.SourceFile;
import org.aya.util.error.SourcePos;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

public class FaithfulPrettierTest {
  @Test public void test() throws IOException {
    var reporter = AyaThrowingReporter.INSTANCE;

    var root = AyaMdParserTest.TEST_DIR;
    var modName = "Main";
    var fileName = modName + ".aya";
    var outputFileName = modName + ".html";

    var sourceFile = new SourceFile(fileName, root, Files.readString(root.resolve(fileName)));
    var parser = new AyaParserImpl(reporter);
    var stmts = parser.program(sourceFile);
    TyckDeclTest.resolve(stmts, new EmptyContext(reporter, root).derive(modName));

    var highlights = SyntaxHighlight.highlight(null, Option.some(sourceFile), stmts);
    var mockPos = new SourcePos(sourceFile, 0, sourceFile.sourceCode().length() - 1, // <- tokenEndIndex is inclusive
      -1, -1, -1, -1);
    var doc = new FaithfulPrettier(ImmutableSeq.empty(), highlights, AyaPrettierOptions.pretty())
      .highlight(sourceFile.sourceCode(), mockPos);
    var output = Doc.codeBlock(Language.Builtin.Aya, doc).renderToHtml(true);
    Files.writeString(root.resolve(outputFileName), output);
  }
}
