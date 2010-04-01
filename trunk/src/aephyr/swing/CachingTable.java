package aephyr.swing;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;

import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListModel;
import javax.swing.RowSorter;
import javax.swing.event.RowSorterEvent;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;

public class CachingTable extends JTable implements Cachable {

	public interface RowData {
		Object getValueAt(int column);
	}
	
	public static class DefaultRowData implements RowData {
		public DefaultRowData(Object[] data) {
			this.data = data;
		}
		
		private Object[] data;
		
		public Object getValueAt(int column) {
			return data[column];
		}
	}
	
	public interface Model extends TableModel {
		
		Callable<RowData> getRowDataAt(int row);
		
		Object getErrorValueAt(int row, int column);
		
	}

	
	public CachingTable(Model model) {
		super(model);
		cachingModel = createCachingModel(model);
	}
	
	private ModelAdapter cachingModel;
	
	private float cacheThreshold = 1.0f;
	
	private int loadingDelay = 300;
	
	private DeferLoading deferLoading;
	
	protected ModelAdapter createCachingModel(Model model) {
		return new ModelAdapter(model);
	}
	
	/**
	 * Determines the cache behavior.
	 * <p>
	 * Float.POSITIVE_INFINITY: All elements are cached and so will only be loaded once.
	 * <p>
	 * 0.0f: Only the visible indices are cached.
	 * <p>
	 * 1.0f: The visible indices and one block increment in either
	 * direction are cached.
	 * <p>
	 * 2.0f: The visible indices and two block increments in either
	 * direction are cached.
	 * <p>
	 * etc.
	 * @param cacheThreshold
	 * @throws IllegalArguemntException if <code>cacheThreshold</code> is less than zero or Float.NaN
	 */
	public void setCacheThreshold(float cacheThreshold) {
		if (cacheThreshold < 0.0f || Float.isNaN(cacheThreshold))
			throw new IllegalArgumentException();
		this.cacheThreshold = cacheThreshold;
	}

	/**
	 * @return cache threshold
	 * @see #setCacheThreshold(float)
	 */
	public float getCacheThreshold() {
		return cacheThreshold;
	}
	
	/**
	 * Sets the delay for the deferred loading. Deferred loading is used when continuous scrolling is in progress.
	 * <p>
	 * A value of 0 disables deferred loading.
	 * 
	 * @param loadingDelay interval in milliseconds to wait for deferred loading
	 * @throws IllegalArgumentException if loadingDelay is less than zero
	 */
	public void setLoadingDelay(int loadingDelay) {
		if (loadingDelay < 0)
			throw new IllegalArgumentException();
		this.loadingDelay = loadingDelay;
		if (loadingDelay == 0) {
			if (deferLoading != null) {
				deferLoading.dispose();
				deferLoading = null;
			}
		} else {
			if (deferLoading != null) {
				deferLoading.setLoadingDelay(loadingDelay);
			} else if (isDisplayable()) {
				initializeDeferLoading();
			}
		}
	}
	
	/**
	 * @return interval in milliseconds to wait for deferred loading
	 * @see #setLoadingDelay(float)
	 */
	public int getLoadingDelay() {
		return loadingDelay;
	}
	
	/**
	 * Clears the cache so that the elements may be garbage collected.
	 * <p>
	 * By default, the cache is cleared when this component becomes undisplayable,
	 * but not when it is hidden.
	 */
	public void clearCache() {
		getCachingModel().clearCache();
	}

	/**
	 * CachingList uses its own internal model to implement the caching/loading behavior.
	 * 
	 * @throws UnsupportedOperationException
	 * @see {@link #setLoadingModel(Model)}
	 */
	public void setModel(TableModel model) {
		if (!(model instanceof Model))
			throw new IllegalArgumentException();
		setModel((Model)model);
	}
	
	public void setModel(Model model) {
		super.setModel(model);
		// cachingModel won't be initialized when this method is
		// called in the super constructor
		if (cachingModel != null)
			cachingModel.setModel(model);
	}
	
	public ModelAdapter getCachingModel() {
		return cachingModel;
	}
	
	private int getFirstVisibleRow(Rectangle visibleRect) {
		return rowAtPoint(new Point(
				visibleRect.x, visibleRect.y));
	}
	
	private int getLastVisibleRow(Rectangle visibleRect) {
		return rowAtPoint(new Point(
				visibleRect.x, visibleRect.y+visibleRect.height-1));
	}

	@Override
	public int getFirstVisibleIndex() {
		return getFirstVisibleRow(getVisibleRect());
	}

	@Override
	public int getLastVisibleIndex() {
		return getLastVisibleRow(getVisibleRect());
	}

	@Override
	public void addNotify() {
		super.addNotify();
		if (loadingDelay > 0)
			initializeDeferLoading();
	}
	
	private void initializeDeferLoading() {
		for (Container p=getParent(); p!=null; p=p.getParent()) {
			if (p instanceof JScrollPane) {
				deferLoading = new DeferLoading(this, (JScrollPane)p);
				break;
			}
		}
	}

	@Override
	public void removeNotify() {
		super.removeNotify();
		if (deferLoading != null) {
			deferLoading.dispose();
			deferLoading = null;
		}
		clearCache();
	}
	

	/**
	 * Overridden to reset the range of caching before painting.
	 * No actual custom painting is performed.
	 */
	@Override
	protected void paintComponent(Graphics g) {
		int offset;
		int length = getModel().getRowCount();
		if (Float.isInfinite(cacheThreshold)) {
			offset = 0;
		} else {
			Rectangle visible = getVisibleRect();
			int firstIndex = getFirstVisibleRow(visible);
			int lastIndex = getLastVisibleRow(visible);
			int range = lastIndex - firstIndex + 1;
			range = Math.round(range * cacheThreshold);
			firstIndex -= range;
			lastIndex += range;
			range = lastIndex - firstIndex + 1;
			if (range < length) {
				offset = firstIndex;
				length = range;
			} else {
				offset = 0;
			}
		}
		getCachingModel().setCacheRange(offset, length);
		super.paintComponent(g);
	}
	
	private boolean scrollingKeyPressed = false;
	
	@Override
	protected void processKeyEvent(KeyEvent e) {
		super.processKeyEvent(e);
		if (deferLoading != null) {
			switch (e.getID()) {
			case KeyEvent.KEY_PRESSED:
				if (isScrollingKey(e)) {
					// only want to start defer loading if two consecutive
					// key presses come without a key release
					if (scrollingKeyPressed) {
						deferLoading.start();
					} else {
						scrollingKeyPressed = true;
					}
				}
				break;
			case KeyEvent.KEY_RELEASED:
				if (isScrollingKey(e)) {
					scrollingKeyPressed = false;
					deferLoading.stop();
				}
				break;
			}
		}
	}
	
	private boolean isScrollingKey(KeyEvent e) {
		switch (e.getKeyCode()) {
		case KeyEvent.VK_PAGE_UP: case KeyEvent.VK_PAGE_DOWN:
		case KeyEvent.VK_UP: case KeyEvent.VK_DOWN:
		case KeyEvent.VK_LEFT: case KeyEvent.VK_RIGHT:
			return true;
		}
		return false;
	}
	
	@Override
	public void sorterChanged(RowSorterEvent e) {
		if (e.getType() == RowSorterEvent.Type.SORTED)
			getCachingModel().clearCache();
		super.sorterChanged(e);
	}
	
	@Override
	public void tableChanged(TableModelEvent e) {
		if (e.getLastRow() == Integer.MAX_VALUE) {
			getCachingModel().clearCache();
		} else if (e.getFirstRow() != TableModelEvent.HEADER_ROW) {
			getCachingModel().updateCache(e);
		}
		super.tableChanged(e);
	}
	
	@Override
	public Object getValueAt(int row, int column) {
		return getCachingModel().getValueAt(
				row, convertColumnIndexToModel(column));
	}
	
	protected class ModelAdapter extends CachingModel {
		
		public ModelAdapter(Model model) {
			this.model = model;
		}
		
		private Model model;
		
		public Model getModel() {
			return model;
		}
		
		public void setModel(Model mdl) {
			if (mdl == null)
				throw new NullPointerException();
			clearCache();
			model = mdl;
		}

		@Override
		protected Object getErrorAt(int row, Exception e) {
			row = convertRowIndexToModel(row);
			Object[] data = new Object[model.getColumnCount()];
			for (int col = data.length; --col>=0;)
				data[col] = model.getErrorValueAt(row, col);
			return new DefaultRowData(data);
		}

		@Override
		protected Callable<?> getLoaderAt(int row) {
			row = convertRowIndexToModel(row);
			return model.getRowDataAt(row);
		}

		@Override
		protected void fireUpdate(int row) {
			Rectangle r = getCellRect(row, 0, true);
			r.x = 0;
			r.width = getWidth();
			repaint(r);
		}
		
		public Object getValueAt(int viewRow, int modelColumn) {
			Object value = getCachedAt(viewRow);
			if (value instanceof RowData)
				return ((RowData)value).getValueAt(modelColumn);
			return model.getValueAt(
					convertRowIndexToModel(viewRow), modelColumn);
		}

		private boolean isUnsorted() {
			RowSorter<?> sorter = getRowSorter();
			if (sorter == null)
				return true;
			if (sorter.getClass() == TableRowSorter.class && sorter.getSortKeys().isEmpty())
				return ((TableRowSorter<?>)sorter).getRowFilter() == null;
			return false;
		}
		
		void updateCache(TableModelEvent e) {
			int firstRow = e.getFirstRow();
			int lastRow = e.getLastRow();
			if (isUnsorted()) {
				updateCache(e.getType(), firstRow, lastRow);
			} else if (firstRow == lastRow) {
				firstRow = convertRowIndexToView(firstRow);
				updateCache(e.getType(), firstRow, firstRow);
			} else {
				int[] rows = new int[lastRow - firstRow + 1];
				for (int i=rows.length; --i>=0;)
					rows[i] = convertRowIndexToView(firstRow + i);
				Arrays.sort(rows);
				int type = e.getType();
				int i = rows.length - 1;
				lastRow = rows[i];
				firstRow = lastRow;
				while (--i>=0) {
					int row = rows[i];
					if (row != firstRow-1) {
						updateCache(type, firstRow, lastRow);
						lastRow = row;
					}
					firstRow = row;
				}
				updateCache(type, firstRow, lastRow);
			}
		}
		
	}
	
	
}
