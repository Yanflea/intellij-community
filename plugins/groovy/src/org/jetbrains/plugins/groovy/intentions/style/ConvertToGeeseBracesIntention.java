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
package org.jetbrains.plugins.groovy.intentions.style;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.GroovyFileType;
import org.jetbrains.plugins.groovy.formatter.GroovyCodeStyleSettings;
import org.jetbrains.plugins.groovy.intentions.base.Intention;
import org.jetbrains.plugins.groovy.intentions.base.PsiElementPredicate;
import org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes;
import org.jetbrains.plugins.groovy.lang.lexer.TokenSets;

import static org.jetbrains.plugins.groovy.formatter.GeeseUtil.*;

/**
 * @author Max Medvedev
 */
public class ConvertToGeeseBracesIntention extends Intention {
  private static final Logger LOG = Logger.getInstance(ConvertToGeeseBracesIntention.class);

  private static final PsiElementPredicate MY_PREDICATE = new PsiElementPredicate() {
    @Override
    public boolean satisfiedBy(PsiElement element) {
      if (element.getLanguage() != GroovyFileType.GROOVY_LANGUAGE) return false;
      if (!CodeStyleSettingsManager.getInstance(element.getProject()).getCurrentSettings()
        .getCustomSettings(GroovyCodeStyleSettings.class).USE_FLYING_GEESE_BRACES) {
        return false;
      }

      IElementType elementType = element.getNode().getElementType();
      if (TokenSets.WHITE_SPACES_SET.contains(elementType)) {
        element = PsiTreeUtil.prevLeaf(element);
      }

      if (!isClosureRBrace(element) || !isClosureContainLF(element)) return false;

      TextRange range = findRange(element);

      return StringUtil.contains(element.getContainingFile().getText(), range.getStartOffset(), range.getEndOffset(), '\n');
    }
  };

  @Nullable
  private static PsiElement getPrev(PsiElement element) {
    PsiElement prev = getPreviousNonWhitespaceToken(element);
    if (prev != null && prev.getNode().getElementType() == GroovyTokenTypes.mNLS) {
      prev = getPreviousNonWhitespaceToken(prev);
    }
    return prev;
  }

  @Nullable
  private static PsiElement getNext(PsiElement element) {
    PsiElement next = getNextNonWhitespaceToken(element);
    if (next != null && next.getNode().getElementType() == GroovyTokenTypes.mNLS) next = getNextNonWhitespaceToken(next);
    return next;
  }

  @Override
  protected void processIntention(PsiElement element, Project project, Editor editor) throws IncorrectOperationException {
    IElementType elementType = element.getNode().getElementType();
    if (TokenSets.WHITE_SPACES_SET.contains(elementType)) {
      element = PsiTreeUtil.prevLeaf(element);
    }
    LOG.assertTrue(isClosureRBrace(element) && isClosureContainLF(element));

    PsiDocumentManager.getInstance(project).commitAllDocuments();

    PsiFile file = element.getContainingFile();
    Document document = PsiDocumentManager.getInstance(project).getDocument(file);

    TextRange textRange = findRange(element);
    int startOffset = textRange.getStartOffset();
    int endOffset = textRange.getEndOffset();

    RangeMarker rangeMarker = document.createRangeMarker(textRange);

    String text = document.getText();
    for (int i = endOffset - 1; i >= startOffset; i--) {
      if (text.charAt(i) == '\n') document.deleteString(i, i + 1);
    }

    CodeStyleManager.getInstance(project).reformatText(file, rangeMarker.getStartOffset(), rangeMarker.getEndOffset());
  }

  private static TextRange findRange(PsiElement element) {
    PsiElement first = null;
    PsiElement last = null;
    for (PsiElement cur = element; isClosureRBrace(cur) && isClosureContainLF(cur); cur = getNext(cur)) {
      last = cur;
    }

    for (PsiElement cur = element; isClosureRBrace(cur) && isClosureContainLF(cur); cur = getPrev(cur)) {
      first = cur;
    }

    LOG.assertTrue(first != null);
    LOG.assertTrue(last != null);


    return new TextRange(first.getTextRange().getStartOffset(), last.getTextRange().getEndOffset());
  }


  @NotNull
  @Override
  protected PsiElementPredicate getElementPredicate() {
    return MY_PREDICATE;
  }
}
