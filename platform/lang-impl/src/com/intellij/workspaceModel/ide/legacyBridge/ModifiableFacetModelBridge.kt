// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.workspaceModel.ide.legacyBridge

import com.intellij.facet.ModifiableFacetModel
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
interface ModifiableFacetModelBridge : ModifiableFacetModel {
  fun prepareForCommit()
}
