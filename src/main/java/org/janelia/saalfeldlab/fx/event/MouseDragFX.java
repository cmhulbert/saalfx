/*-
 * #%L
 * Saalfeld lab JavaFX tools and extensions
 * %%
 * Copyright (C) 2019 Philipp Hanslovsky, Stephan Saalfeld
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package org.janelia.saalfeldlab.fx.event;

import java.lang.invoke.MethodHandles;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class MouseDragFX implements InstallAndRemove<Node>
{

	private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	protected double startX = 0;

	protected double startY = 0;

	private final SimpleBooleanProperty isDragging = new SimpleBooleanProperty();

	private final DragDetect detect = new DragDetect();

	private final Drag drag = new Drag();

	private final DragRelease release = new DragRelease();

	private final String name;

	private final Predicate<MouseEvent> eventFilter;

	protected final Object transformLock;

	protected final boolean consume;

	protected final boolean updateXY;

	public MouseDragFX(
			final String name,
			final Predicate<MouseEvent> eventFilter,
			final Object transformLock,
			final boolean updateXY)
	{
		this(name, eventFilter, false, transformLock, updateXY);
	}

	public MouseDragFX(
			final String name,
			final Predicate<MouseEvent> eventFilter,
			final boolean consume,
			final Object transformLock,
			final boolean updateXY)
	{
		super();
		this.name = name;
		this.eventFilter = eventFilter;
		this.transformLock = transformLock;
		this.consume = consume;
		this.updateXY = updateXY;
	}

	public abstract void initDrag(MouseEvent event);

	public abstract void drag(MouseEvent event);

	public void endDrag(final MouseEvent event)
	{
	}

	public String name()
	{
		return name;
	}

	@Override
	public void installInto(final Node node)
	{
		node.addEventHandler(MouseEvent.DRAG_DETECTED, detect);
		node.addEventHandler(MouseEvent.MOUSE_DRAGGED, drag);
		node.addEventHandler(MouseEvent.MOUSE_RELEASED, release);
	}

	@Override
	public void removeFrom(final Node node)
	{
		node.removeEventHandler(MouseEvent.DRAG_DETECTED, detect);
		node.removeEventHandler(MouseEvent.MOUSE_DRAGGED, drag);
		node.removeEventHandler(MouseEvent.MOUSE_RELEASED, release);
	}

	public void installIntoAsFilter(final Node node)
	{
		node.addEventFilter(MouseEvent.DRAG_DETECTED, detect);
		node.addEventFilter(MouseEvent.MOUSE_DRAGGED, drag);
		node.addEventFilter(MouseEvent.MOUSE_RELEASED, release);
	}

	public void removeFromAsFilter(final Node node)
	{
		node.removeEventFilter(MouseEvent.DRAG_DETECTED, detect);
		node.removeEventFilter(MouseEvent.MOUSE_DRAGGED, drag);
		node.removeEventFilter(MouseEvent.MOUSE_RELEASED, release);
	}

	private class DragDetect implements EventHandler<MouseEvent>
	{

		@Override
		public void handle(final MouseEvent event)
		{
			if (eventFilter.test(event))
			{
				startX = event.getX();
				startY = event.getY();
				isDragging.set(true);
				initDrag(event);
				if (consume)
				{
					LOG.debug("Consuming Drag Detect event");
					event.consume();
				}
			}
		}
	}

	private class Drag implements EventHandler<MouseEvent>
	{

		@Override
		public void handle(final MouseEvent event)
		{
			if (isDragging.get())
			{
				drag(event);
				if (consume)
				{
					LOG.debug("Consuming Drag event");
					event.consume();
				}
				if (updateXY)
				{
					startX = event.getX();
					startY = event.getY();
				}
			}

		}
	}

	private class DragRelease implements EventHandler<MouseEvent>
	{

		@Override
		public void handle(final MouseEvent event)
		{
			final boolean wasDragging = isDragging.get();
			isDragging.set(false);
			if (wasDragging)
			{
				endDrag(event);
				if (consume)
				{
					LOG.debug("Consuming DragRelease event");
					event.consume();
				}
			}
		}

	}

	public ObservableBooleanValue isDraggingProperty()
	{
		return this.isDragging;
	}

	public void abortDrag()
	{
		this.isDragging.set(false);
	}

	public static MouseDragFX createDrag(
			final String name,
			final Predicate<MouseEvent> eventFilter,
			final boolean consume,
			final Object transformLock,
			final Consumer<MouseEvent> initDrag,
			final BiConsumer<Double, Double> drag,
			final boolean updateXY)
	{
		return new MouseDragFX(name, eventFilter, consume, transformLock, updateXY)
		{

			@Override
			public void initDrag(final MouseEvent event)
			{
				initDrag.accept(event);
			}

			@Override
			public void drag(final MouseEvent event)
			{
				drag.accept(event.getX() - startX, event.getY() - startY);
			}
		};
	}

}
