/*
 * Copyright 2000-2011 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.psi.impl.source.tree;

import com.intellij.lang.ASTFactory;
import com.intellij.lang.DefaultASTFactory;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.psi.impl.source.Constants;
import com.intellij.psi.impl.source.javadoc.CorePsiDocTagValueImpl;
import com.intellij.psi.impl.source.javadoc.PsiDocTokenImpl;
import com.intellij.psi.impl.source.tree.java.PsiIdentifierImpl;
import com.intellij.psi.impl.source.tree.java.PsiJavaTokenImpl;
import com.intellij.psi.impl.source.tree.java.PsiKeywordImpl;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.java.IJavaDocElementType;
import com.intellij.psi.tree.java.IJavaElementType;

/**
 * @author yole
 */
public class CoreJavaASTFactory extends ASTFactory implements Constants {
  private final DefaultASTFactory myDefaultASTFactory = ServiceManager.getService(DefaultASTFactory.class);

  @Override
  public LeafElement createLeaf(final IElementType type, final CharSequence text) {
    if (type == C_STYLE_COMMENT || type == END_OF_LINE_COMMENT) {
      return myDefaultASTFactory.createComment(type, text);
    }
    else if (type == IDENTIFIER) {
      return new PsiIdentifierImpl(text);
    }
    else if (KEYWORD_BIT_SET.contains(type)) {
      return new PsiKeywordImpl(type, text);
    }
    else if (type instanceof IJavaElementType) {
      return new PsiJavaTokenImpl(type, text);
    }
    else if (type instanceof IJavaDocElementType) {
      return new PsiDocTokenImpl(type, text);
    }

    return null;
  }

  @Override
  public CompositeElement createComposite(IElementType type) {
    if (type == DOC_TAG_VALUE_TOKEN) {
      return new CorePsiDocTagValueImpl();
    }
    return null;
  }
}
