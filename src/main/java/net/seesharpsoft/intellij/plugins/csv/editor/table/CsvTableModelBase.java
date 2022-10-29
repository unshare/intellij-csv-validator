package net.seesharpsoft.intellij.plugins.csv.editor.table;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import net.seesharpsoft.intellij.plugins.csv.CsvHelper;
import net.seesharpsoft.intellij.plugins.csv.editor.table.api.CsvTableModel;
import net.seesharpsoft.intellij.plugins.csv.psi.CsvField;
import net.seesharpsoft.intellij.plugins.csv.psi.CsvPsiTreeUpdater;
import net.seesharpsoft.intellij.plugins.csv.psi.CsvRecord;
import net.seesharpsoft.intellij.plugins.csv.psi.CsvTypes;
import net.seesharpsoft.intellij.plugins.csv.settings.CsvEditorSettings;
import net.seesharpsoft.intellij.psi.PsiFileHolder;
import net.seesharpsoft.intellij.psi.PsiHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

public abstract class CsvTableModelBase<T extends PsiFileHolder> implements CsvTableModel {
    private final T myPsiFileHolder;

    private int myCachedRowCount = -1;
    private int myCachedColumnCount = -1;
    private Boolean myCachedHasErrors = null;

    private int myPointerRow = -1;
    private CsvField myPointerElement = null;

    private final CsvPsiTreeUpdater myPsiTreeUpdater;

    private final PsiTreeChangeListener myPsiTreeChangeListener = new PsiTreeAnyChangeAbstractAdapter() {
        @Override
        protected void onChange(@Nullable PsiFile file) {
            onPsiTreeChanged(myPsiTreeUpdater.getPsiFile());
        }
    };

    public CsvTableModelBase(@NotNull T psiFileHolder) {
        myPsiFileHolder = psiFileHolder;
        myPsiTreeUpdater = new CsvPsiTreeUpdater(psiFileHolder);
        myPsiTreeUpdater.addCommitListener(() -> onPsiTreeChanged(getPsiFile()));
        getPsiFile().getManager().addPsiTreeChangeListener(myPsiTreeChangeListener, myPsiFileHolder);
    }

    public T getPsiFileHolder() {
        return myPsiFileHolder;
    }

    @Override
    public void dispose() {
        CsvTableModel.super.dispose();
        getPsiFile().getManager().removePsiTreeChangeListener(myPsiTreeChangeListener);
        myPsiTreeUpdater.dispose();
    }

    private void onPsiTreeChanged(@Nullable PsiFile file) {
        if (file == getPsiFile() && !myPsiTreeUpdater.isSuspended() && !isSuspended()) {
            notifyUpdate();
        }
    }

    @Override
    public PsiFile getPsiFile() {
        return myPsiFileHolder.getPsiFile();
    }

    @Override
    public void notifyUpdate() {
        this.resetCachedValues();
        this.resetPointer();
    }

    private void resetCachedValues() {
        myCachedRowCount = -1;
        myCachedColumnCount = -1;
        myCachedHasErrors = null;
    }

    private CsvField resetPointer() {
        myPointerElement = PsiTreeUtil.findChildOfType(getPsiFile(), CsvField.class);
        myPointerRow = 0;
        return myPointerElement;
    }

    protected CsvPsiTreeUpdater getPsiTreeUpdater() {
        return myPsiTreeUpdater;
    }

    @Override
    public PsiElement getFieldAt(int row, int column) {
        int diffToCurrent = Math.abs(myPointerRow - row);
        if (diffToCurrent > row) {
            resetPointer();
            diffToCurrent = row;
        }

        CsvRecord record = PsiHelper.getNthSiblingOfType(myPointerElement.getParent(), diffToCurrent, CsvRecord.class, myPointerRow > row);
        if (record == null) return null;

        if (PsiHelper.getElementType(record.getFirstChild()) == CsvTypes.COMMENT) return record.getFirstChild();

        CsvField field = PsiHelper.getNthChildOfType(record, column, CsvField.class);
        if (field == null) return null;

        myPointerElement = field;
        myPointerRow = row;
        return myPointerElement;
    }

    @Override
    public boolean hasErrors() {
        if (myCachedHasErrors == null) {
            myCachedHasErrors = CsvTableModel.super.hasErrors();
        }
        return myCachedHasErrors;
    }

    @Override
    public int getRowCount() {
        if (myCachedRowCount == -1) {
            myCachedRowCount = CsvTableModel.super.getRowCount();
        }
        return myCachedRowCount;
    }

    @Override
    public int getColumnCount() {
        if (myCachedColumnCount == -1) {
            myCachedColumnCount = CsvTableModel.super.getColumnCount();
        }
        return myCachedColumnCount;
    }

    @Override
    public void setValue(String value, int rowIndex, int columnIndex) {
        setValueAt(value, rowIndex, columnIndex, true);
    }

    private void createMissingColumns(int rowIndex, int columnIndex) {
        int currentColumnCount = getColumnCount(rowIndex);
        PsiElement field = getFieldAt(rowIndex, currentColumnCount - 1);
        getPsiTreeUpdater().addEmptyColumns(field, columnIndex - currentColumnCount + 1);
    }

    private int getColumnCount(int rowIndex) {
        return getColumnCount(PsiHelper.getNthChildOfType(getPsiTreeUpdater().getPsiFile(), rowIndex, CsvRecord.class));
    }

    private int getColumnCount(PsiElement record) {
        if (record == null) return -1;
        return PsiTreeUtil.countChildrenOfType(record, CsvField.class);
    }

    public void setValueAt(String value, int rowIndex, int columnIndex, boolean commitImmediately) {
        PsiElement field = getFieldAt(rowIndex, columnIndex);
        CsvPsiTreeUpdater updater = getPsiTreeUpdater();
        if (field == null) {
            int currentColumnCount = getColumnCount(rowIndex);
            field = getFieldAt(rowIndex, currentColumnCount - 1);
            updater.addColumn(field, value, true);
            updater.addEmptyColumns(field, columnIndex - currentColumnCount);
        } else {
            if (CsvHelper.isCommentElement(field)) {
                updater.replaceComment(field, value);
            } else {
                updater.replaceField(field, value, columnIndex == 0);
            }
        }
        updater.commit();
    }

    @Override
    public String getValue(int rowIndex, int columnIndex) {
        PsiElement field = getFieldAt(rowIndex, columnIndex);
        String value = field == null ? "" : field.getText();
        return CsvHelper.isCommentElement(field) ?
                value.substring(CsvEditorSettings.getInstance().getCommentIndicator().length()) :
                CsvHelper.unquoteCsvValue(value, getEscapeCharacter());
    }

    @Override
    public void addRow(int focusedRowIndex, boolean before) {
        CsvRecord row = PsiHelper.getNthChildOfType(getPsiFile(), focusedRowIndex, CsvRecord.class);
        getPsiTreeUpdater().addRow(row, before);
        getPsiTreeUpdater().commit();
    }

    @Override
    public void removeRows(int[] indices) {
        CsvPsiTreeUpdater updater = getPsiTreeUpdater();
        Set<PsiElement> toDelete = new HashSet<>();
        PsiFile psiFile = getPsiFile();
        for (int rowIndex : indices) {
            CsvRecord row = PsiHelper.getNthChildOfType(psiFile, rowIndex, CsvRecord.class);
            boolean removePreviousLF = rowIndex > 0;
            PsiElement lfElement = PsiHelper.getSiblingOfType(row, CsvTypes.CRLF, removePreviousLF);
            if (toDelete.contains(lfElement)) {
                lfElement = PsiHelper.getSiblingOfType(row, CsvTypes.CRLF, !removePreviousLF);
            }
            if (lfElement != null) {
                toDelete.add(row);
                toDelete.add(lfElement);
            }
        }
        updater.delete(toDelete.toArray(new PsiElement[toDelete.size()]));
        updater.commit();
    }

    @Override
    public void addColumn(int focusedColumnIndex, boolean before) {
        CsvPsiTreeUpdater updater = getPsiTreeUpdater();
        // +1 for the one to add
        int targetColumnCount = getColumnCount() + 1;
        int rowIndex = 0;
        for (PsiElement record = getPsiFile().getFirstChild(); record != null; record = record.getNextSibling()) {
            if (!CsvRecord.class.isInstance(record)) continue;
            if (CsvHelper.isCommentElement(record.getFirstChild())) continue;
            PsiElement focusedCol = PsiHelper.getNthChildOfType(record, focusedColumnIndex, CsvField.class);
            if (focusedCol == null) {
                createMissingColumns(rowIndex, targetColumnCount);
            } else {
                updater.addColumn(focusedCol, before);
            }
            ++rowIndex;
        }
        updater.commit();
    }

    @Override
    public void removeColumns(int[] indices) {
        if (indices.length == 0) return;

        CsvPsiTreeUpdater updater = getPsiTreeUpdater();
        if (getColumnCount() == 1) {
            updater.deleteContent();
            return;
        }

        Set<PsiElement> toDelete = new HashSet<>();
        for (PsiElement record = getPsiFile().getFirstChild(); record != null; record = record.getNextSibling()) {
            if (!CsvRecord.class.isInstance(record)) continue;
            if (CsvHelper.isCommentElement(record.getFirstChild())) continue;
            for (int columnIndex : indices) {
                PsiElement focusedCol = PsiHelper.getNthChildOfType(record, columnIndex, CsvField.class);
                // if no field exists in row, we are done
                if (focusedCol != null) {
                    boolean removePreviousSeparator = columnIndex > 0;
                    PsiElement valueSeparator = PsiHelper.getSiblingOfType(focusedCol, CsvTypes.COMMA, removePreviousSeparator);
                    if (toDelete.contains(valueSeparator)) {
                        valueSeparator = PsiHelper.getSiblingOfType(focusedCol, CsvTypes.COMMA, !removePreviousSeparator);
                    }
                    if (valueSeparator != null) {
                        toDelete.add(focusedCol);
                        toDelete.add(valueSeparator);
                    }
                }
            }
        }

        updater.delete(toDelete.toArray(new PsiElement[toDelete.size()]));
        updater.commit();
    }

    @Override
    public void clearCells(int[] rows, int[] columns) {
        for (int currentColumn : columns) {
            for (int currentRow : rows) {
                setValueAt("", currentRow, currentColumn, false);
            }
        }
        getPsiTreeUpdater().commit();
    }
}
