/*
 * AutoRefactor - Eclipse plugin to automatically refactor Java code bases.
 *
 * Copyright (C) 2014 Jean-Noël Rouvignac - initial API and implementation
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program under LICENSE-GNUGPL.  If not, see
 * <http://www.gnu.org/licenses/>.
 *
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution under LICENSE-ECLIPSE, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.autorefactor.ui;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.autorefactor.refactoring.IRefactoring;
import org.autorefactor.util.UnhandledException;
import org.eclipse.jface.viewers.*;
import org.eclipse.jface.viewers.StyledString.Styler;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

import static org.eclipse.jface.viewers.CheckboxTableViewer.*;

public class ChooseRefactoringWizardPage extends WizardPage {

	/** TODO JNR replace this it is not good enough. */
	private enum ModelProvider {
		INSTANCE;

		private List<IRefactoring> refactorings;

		@SuppressWarnings("unchecked")
		private ModelProvider() {
			try {
				final Method m = AutoRefactorHandler.class.getDeclaredMethod("getAllRefactorings");
				m.setAccessible(true);
				refactorings = (List<IRefactoring>) m.invoke(null);
			}
			catch (Exception e) {
				throw new UnhandledException(e);
			}
		}

		public List<IRefactoring> getRefactorings() {
			return refactorings;
		}

	}

	private final class CheckStateProvider implements ICheckStateProvider {

		public CheckStateProvider(List<? extends Object> refactorings) {
			for (Object refactoring : refactorings) {
				checkedState.put(refactoring, Boolean.FALSE);
			}
		}

		public boolean isChecked(Object element) {
			return Boolean.TRUE.equals(checkedState.get(element));
		}

		public boolean isGrayed(Object element) {
			return false;
		}
	}

	private final HashMap<Object, Boolean> checkedState = new HashMap<Object, Boolean>();
	private Text filterText;
	private CheckboxTableViewer tableViewer;
	private Button selectAllVisibleCheckbox;

	private final Styler defaultStyler = new Styler() {
		@Override
		public void applyStyles(TextStyle textStyle) {
			// no specific style
		}
	};

	private final Styler underlineStyler = new Styler() {
		@Override
		public void applyStyles(TextStyle style) {
			style.underline = true;
		}
	};


	ChooseRefactoringWizardPage() {
		super("Choose refactorings...");
		setTitle("Choose refactorings...");
		setDescription("Choose the refactorings to perform automatically");
	}

	public List<IRefactoring> getSelectedRefactorings() {
		final ArrayList<IRefactoring> results = new ArrayList<IRefactoring>();
		for (Object o : tableViewer.getCheckedElements()) {
			results.add((IRefactoring) o);
		}
		return results;
	}

	/** {@inheritDoc} */
	public void createControl(Composite parent) {
		parent.setLayout(new GridLayout());

		createFilterText(parent);
		createSelectAllCheckbox(parent);
		createRefactoringsTable(parent);

		// required to avoid an error in the system
		setControl(parent);
		// Allows to click the "Finish" button
		setPageComplete(true);
	}

	private void createFilterText(Composite parent) {
		filterText = new Text(parent, SWT.BORDER | SWT.SINGLE);
		filterText.setMessage("Type in to filter refactorings");

		filterText.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent event) {
				// trigger a call to StyledCellLabelProvider.update()
				tableViewer.refresh(true);
			}
		});
	}

	private void createSelectAllCheckbox(Composite parent) {
		selectAllVisibleCheckbox = new Button(parent, SWT.CHECK);
		selectAllVisibleCheckbox.setText("(de)select all visible refactorings");
		selectAllVisibleCheckbox.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent event) {
				final Object[] visibleElements = filter(tableViewer, tableViewer.getInput());
				for (Object element : visibleElements) {
					setChecked(element, selectAllVisibleCheckbox.getSelection());
				}
			}

			private Object[] filter(StructuredViewer viewer, Object input) {
				try {
					final Class<StructuredViewer> clazz = StructuredViewer.class;
					Method m = clazz.getDeclaredMethod("filter", Object[].class);
					m.setAccessible(true);
					return (Object[]) m.invoke(viewer, (Object) ((List<?>) input).toArray());
				}
				catch (Exception e) {
					throw new UnhandledException(e);
				}
			}
		});
	}

	private void setChecked(Object element, boolean isChecked) {
		checkedState.put(element, isChecked);
		tableViewer.setChecked(element, isChecked);
	}

	private void createRefactoringsTable(Composite parent) {
		tableViewer = newCheckList(parent,
				SWT.BORDER | SWT.H_SCROLL | SWT.CHECK | SWT.NO_FOCUS | SWT.HIDE_SELECTION);
		createColumns(tableViewer);
		tableViewer.setContentProvider(new ArrayContentProvider());
		final List<IRefactoring> refactorings = ModelProvider.INSTANCE.getRefactorings();
		tableViewer.setInput(refactorings);
		tableViewer.setCheckStateProvider(new CheckStateProvider(refactorings));
		tableViewer.setComparator(new ViewerComparator() {
			@Override
			public int compare(Viewer viewer, Object o1, Object o2) {
				// o1 and o2 are IRefactoring objects
				return o1.getClass().getSimpleName().compareTo(
						o2.getClass().getSimpleName());
			}
		});
		tableViewer.addFilter(new ViewerFilter() {
			@Override
			public boolean select(Viewer viewer, Object parentElement, Object refactoring) {
				final String filter = filterText.getText().toLowerCase();
				final String cellText = classnameToText(refactoring);
				return cellText.toLowerCase().indexOf(filter) != -1;
			}
		});
		tableViewer.setLabelProvider(new StyledCellLabelProvider() {
			@Override
			public void update(ViewerCell cell) {
				final String filter = filterText.getText().toLowerCase();
				final String cellText = classnameToText(cell.getElement());
				final int idx = cellText.toLowerCase().indexOf(filter);
				cell.setText(cellText);
				cell.setStyleRanges(getStyleRanges(cellText, filter, idx));
			}

			private StyleRange[] getStyleRanges(String text, String filter,
					int matchIndex) {
				final int matchLength = filter.length();
				if (matchIndex != -1 && matchLength != 0) {
					final StyledString styledString =
							new StyledString(text, defaultStyler);
					styledString.setStyle(matchIndex, matchLength, underlineStyler);
					return styledString.getStyleRanges();
				}
				return null;
			}
		});
		tableViewer.addSelectionChangedListener(new ISelectionChangedListener() {
			public void selectionChanged(SelectionChangedEvent event) {
				final ISelection sel = event.getSelection();
				if (sel instanceof IStructuredSelection) {
					final IStructuredSelection sel2 = (IStructuredSelection) sel;
					if (!sel2.isEmpty()) {
						final Object element = sel2.getFirstElement();
						setChecked(element, !tableViewer.getChecked(element));
					}
				}
			}
		});

		final Table table = tableViewer.getTable();
		table.setLinesVisible(true);
		tableViewer.getControl().setLayoutData(
				new GridData(SWT.FILL, SWT.TOP, true, false));
		packColumns(table);
		table.setFocus();
	}

	private void createColumns(final TableViewer tableViewer) {
		TableViewerColumn refactoringColumn =
				createTableViewerColumn(tableViewer);
		refactoringColumn.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				return classnameToText(element);
			}
		});
	}

	private String classnameToText(final Object r) {
		String name = r.getClass().getSimpleName();
		final int idx = name.indexOf("Refactoring");
		if (idx >= 0) {
			name = name.substring(0, idx);
		}

		final StringBuilder sb = new StringBuilder();
		final Pattern p = Pattern.compile("([A-Z][^A-Z]+)");
		final Matcher m = p.matcher(name);
		boolean isFirst = true;
		int lastMatchIdx = 0;
		while (m.find()) {
			final String matched = m.group();
			lastMatchIdx = m.end();
			if (isFirst) {
				sb.append(matched);
				sb.append(" ");
				isFirst = false;
				continue;
			}
			final char firtChar = matched.charAt(0);
			if (Character.isUpperCase(firtChar)) {
				sb.append(Character.toLowerCase(firtChar));
				sb.append(matched, 1, matched.length());
				sb.append(" ");
			}
		}
		if (lastMatchIdx < name.length()) {
			sb.append(name, lastMatchIdx, name.length());
		}
		return sb.toString();
	}

	private TableViewerColumn createTableViewerColumn(TableViewer tableViewer) {
		final TableViewerColumn viewerColumn =
				new TableViewerColumn(tableViewer, SWT.NONE);
		final TableColumn column = viewerColumn.getColumn();
		column.setResizable(true);
		column.setMoveable(true);
		return viewerColumn;
	}

	private void packColumns(final Table table) {
		final int length = table.getColumns().length;
		for (int i = 0; i < length; i++) {
			table.getColumn(i).pack();
		}
	}

	/** {@inheritDoc} */
	@Override
	public void dispose() {
		checkedState.clear();
		filterText.dispose();
		tableViewer.getTable().dispose();
		selectAllVisibleCheckbox.dispose();
		super.dispose();
	}
}
