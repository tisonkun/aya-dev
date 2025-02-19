// Copyright (c) 2020-2023 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.lsp;

import com.google.gson.Gson;
import kala.collection.immutable.ImmutableSeq;
import org.aya.cli.render.RenderOptions;
import org.aya.concrete.Pattern;
import org.aya.concrete.stmt.decl.TeleDecl;
import org.aya.core.term.DataCall;
import org.aya.core.term.MetaPatTerm;
import org.aya.generic.Constants;
import org.aya.lsp.models.ServerOptions;
import org.aya.lsp.models.ServerRenderOptions;
import org.aya.lsp.tester.LspTestClient;
import org.aya.lsp.tester.LspTestCompilerAdvisor;
import org.javacs.lsp.InitializeParams;
import org.javacs.lsp.Position;
import org.javacs.lsp.TextDocumentIdentifier;
import org.javacs.lsp.TextDocumentPositionParams;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.aya.lsp.tester.TestCommand.compile;
import static org.aya.lsp.tester.TestCommand.mutate;
import static org.junit.jupiter.api.Assertions.*;

public class LspTest {
  public static final @NotNull Path TEST_LIB = Path.of("src", "test", "resources", "lsp-test-lib");

  public @NotNull LspTestClient launch(@NotNull Path libraryRoot) {
    var client = new LspTestClient();
    client.registerLibrary(libraryRoot);
    return client;
  }

  @Test public void testJustLoad() {
    launch(TEST_LIB).execute(compile((a, e) -> {}));
  }

  @Test public void testIncremental() {
    launch(TEST_LIB).execute(
      compile((a, e) -> {}),
      mutate("StringPrims"),
      compile((a, e) -> assertRemake(a, e, "StringPrims", "HelloWorld"))
    );
  }

  @Test public void test541() {
    launch(TEST_LIB).execute(compile((a, e) -> {
      var testOpt = a.lastCompiled()
        .filter(x -> x.moduleName().last().equals("Vec"))
        .flatMap(x -> x.program().get())
        .filterIsInstance(TeleDecl.FnDecl.class)
        .filter(x -> x.ref.name().equals("test"))
        .firstOption();
      assertFalse(testOpt.isEmpty(), "Do not delete the function called test in Vec");
      var testClause = ((TeleDecl.BlockBody) testOpt.get().body).clauses().first();
      // vnil, ys => 0
      var testPat = (Pattern.Bind) testClause.patterns.last().term();
      var testTy = assertInstanceOf(DataCall.class, testPat.type().get());
      assertNotNull(testTy);
      // ys : Vec A m
      var lastArg = testTy.args().last().term();
      assertFalse(lastArg instanceof MetaPatTerm);
    }));
  }

  @Test public void testRealWorldLike() {
    launch(TEST_LIB).execute(
      compile((a, e) -> {}),
      mutate("HelloWorld"),
      compile((a, e) -> assertRemake(a, e, "HelloWorld")),
      mutate("NatCore"),
      compile((a, e) -> assertRemake(a, e, "NatCore", "Vec", "HelloWorld")),
      mutate("PathPrims"),
      compile((a, e) -> assertRemake(a, e, "PathPrims", "Path", "HelloWorld"))
    );
  }

  @Test
  public void colorful() {
    var initParams = new InitializeParams();
    initParams.initializationOptions = new Gson().toJsonTree(new ServerOptions(new ServerRenderOptions(null, null, RenderOptions.OutputTarget.HTML)));

    var client = new LspTestClient(initParams);
    client.registerLibrary(TEST_LIB);
    client.execute(compile((a, e) -> {}));

    var param = new TextDocumentPositionParams(new TextDocumentIdentifier(
      TEST_LIB.resolve("src").resolve("NatCore.aya").toUri()),
      new Position(0, 18)
    );

    var result0 = client.service.hover(param);
    assertTrue(result0.isPresent());
    assertEquals("<a href=\"#NatCore-Nat\"><span style=\"color:#218c21;\">Nat</span></a>", result0.get().contents.get(0).value);

    client.service.updateServerOptions(new ServerOptions(new ServerRenderOptions("IntelliJ", null, RenderOptions.OutputTarget.HTML)));

    var result1 = client.service.hover(param);
    assertTrue(result1.isPresent());
    assertEquals("<a href=\"#NatCore-Nat\"><span style=\"color:#000000;\">Nat</span></a>", result1.get().contents.get(0).value);
  }

  private void logTime(long time) {
    System.out.println("Remake changed modules took: " + time + "ms");
  }

  private void assertRemake(@NotNull LspTestCompilerAdvisor advisor, long time, @NotNull String... modules) {
    logTime(time);
    assertNotNull(advisor.lastJob);
    var actualInDep = advisor.newlyCompiled.view()
      .map(r -> r.thisModule().modulePath().toString())
      .toImmutableSeq();
    var actual = advisor.lastCompiled()
      .map(s -> s.moduleName().joinToString(Constants.SCOPE_SEPARATOR))
      .concat(actualInDep)
      .distinct()
      .toImmutableSeq();
    var expected = ImmutableSeq.from(modules);
    assertEquals(expected.sorted(), actual.sorted());
  }
}
