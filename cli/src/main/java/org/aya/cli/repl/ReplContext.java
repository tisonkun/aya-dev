// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.cli.repl;

import kala.collection.Seq;
import kala.collection.immutable.ImmutableSeq;
import kala.value.MutableValue;
import org.aya.cli.utils.RepoLike;
import org.aya.concrete.stmt.Stmt;
import org.aya.ref.DefVar;
import org.aya.resolve.context.*;
import org.aya.util.error.SourcePos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ReplContext extends PhysicalModuleContext implements RepoLike<ReplContext> {
  private final @NotNull MutableValue<@Nullable ReplContext> downstream = MutableValue.create();

  public ReplContext(@NotNull Context parent, @NotNull ImmutableSeq<String> name) {
    super(parent, name);
  }

  @Override
  public void addGlobal(
    @NotNull GlobalSymbol symbol,
    @NotNull SourcePos sourcePos
  ) {
    var modName = symbol.componentName();
    var name = symbol.unqualifiedName();
    symbols().addAnyway(modName, name, symbol.data());

    var export = symbol.exportMaybe();
    if (export != null) {
      this.doExport(modName, name, export, sourcePos);
    }
  }

  @Override
  public void doExport(@NotNull ModulePath component, @NotNull String name, @NotNull DefVar<?, ?> ref, @NotNull SourcePos sourcePos) {
    exports().get(ModulePath.This).exportAnyway(component, name, ref);
  }

  @Override
  public void importModule(
    @NotNull ModulePath.Qualified componentName,
    @NotNull MutableModuleExport mod,
    Stmt.@NotNull Accessibility accessibility,
    @NotNull SourcePos sourcePos
  ) {
    modules.put(componentName, mod);
    if (accessibility == Stmt.Accessibility.Public) exports.set(componentName, mod);
  }

  @Override
  public @NotNull ReplContext derive(@NotNull Seq<@NotNull String> extraName) {
    return new ReplContext(this, this.moduleName().concat(extraName));
  }

  @Override
  public @NotNull ReplContext derive(@NotNull String extraName) {
    return new ReplContext(this, this.moduleName().appended(extraName));
  }

  @Override public @NotNull MutableValue<ReplContext> downstream() {
    return downstream;
  }

  public @NotNull ReplContext fork() {
    var kid = derive(":theKid");
    fork(kid);
    return kid;
  }

  @Override public void merge() {
    var bors = downstream.get();
    RepoLike.super.merge();
    if (bors == null) return;
    this.symbols.table().putAll(bors.symbols.table());
    this.exports.putAll(bors.exports);
    this.modules.putAll(bors.modules);
  }
}
