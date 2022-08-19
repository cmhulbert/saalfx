package org.janelia.saalfeldlab.fx.ui

import com.sun.javafx.application.PlatformImpl
import javafx.application.Platform
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.ListChangeListener
import javafx.collections.ObservableList
import javafx.event.EventHandler
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.input.ClipboardContent
import javafx.scene.input.TransferMode
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.stage.Stage
import org.janelia.saalfeldlab.fx.extensions.nullable

private enum class Extend {
    TOP,
    LEFT,
    BOTTOM,
    RIGHT
}
class DetachableTreePane() : StackPane() {

    val draggingTabProperty = SimpleObjectProperty<Tab>()
    var draggingTab by draggingTabProperty.nullable()
    val tabpPaneOverProperty = SimpleObjectProperty<TabPane>()
    var tabPaneOver: TabPane? by tabpPaneOverProperty.nullable()
    private var extendSplitPaneDirection: Extend? = null

    init {
        children.setAll(newSplitPaneTreeNode())
        alignment = Pos.TOP_LEFT
    }

    private fun addOverlay() {
        if (!children.contains(overlay)) {
            children.add(overlay)
        }
    }

    private fun removeOverlay() {
        children.remove(overlay)
        extendSplitPaneDirection = null

    }


    companion object {
        private const val TAB_DRAG_KEY = "tab"
        private val overlay = Rectangle().apply {
            opacity = .5
            isPickOnBounds = false
            isMouseTransparent = true
            fill = Color.HOTPINK
        }
    }


    fun newSourceTab(text: String, node: Node): Tab {
        return newSourceTab(Label(text), node)
    }

    fun newSourceTab(graphic: Node, node: Node): Tab {
        val tab = Tab()
        tab.graphic = graphic
        tab.content = node
        graphic.onDragDetected = EventHandler { event ->
            val dragboard = graphic.startDragAndDrop(TransferMode.MOVE)
            dragboard.setDragView(tab.content.snapshot(SnapshotParameters(), null), -10.0, -10.0)
            val clipboardContent = ClipboardContent().also { it.putString(TAB_DRAG_KEY) }
            dragboard.setContent(clipboardContent)
            draggingTab = tab
            event.consume()
        }
        graphic.onDragDone = EventHandler { event ->
            event.consume()
        }
        return tab
    }

    private fun TabPane.initDraggableTabReceiver() {
        listOf(widthProperty(), heightProperty()).forEach {
            it.addListener { _, old, new ->
                if (old != new) requestLayout()
            }
        }
        tabs.addListener(ListChangeListener<Tab> {
            if (it.list.isEmpty()) {
                (this.parent.parent as? SplitPane)?.let { sp ->
                    sp.items.remove(this)
                    if (sp.items.isEmpty()) {
                        (sp.parent?.parent as? SplitPane)?.apply {
                            items.remove(sp)
                        } ?: let {
                            (sp.parent as? StackPane)?.apply {
                                children.remove(sp)
                            }
                        }
                    }
                }
            }
        })
        onDragOver = EventHandler {
            val lastTabOnThisPane = this.tabs.contains(draggingTab) and (this.tabs.size <= 1)
            val hasTabDragKey = it.dragboard.string == TAB_DRAG_KEY

            if (!lastTabOnThisPane && hasTabDragKey) {
                it.acceptTransferModes(TransferMode.MOVE)
                val nodeWidth = this.width
                val nodeHeight = this.height
                var minX = 0.0
                var minY = 0.0
                var rectWidth = nodeWidth
                var rectHeight = nodeHeight
                if (this.parent.parent is SplitPane) {
                    val left10 = nodeWidth * .10
                    val right40 = nodeWidth - nodeWidth * .40
                    val right10 = nodeWidth - nodeWidth * .10
                    val top10 = nodeHeight * .10
                    val bottom40 = nodeHeight - nodeHeight * .40
                    val bottom10 = nodeHeight - nodeHeight * .10
                    if (it.y < top10) {
                        rectHeight = nodeHeight * .40
                        extendSplitPaneDirection = Extend.TOP
                    } else if (it.y > bottom10) {
                        minY = bottom40
                        rectHeight = nodeHeight * .40
                        extendSplitPaneDirection = Extend.BOTTOM
                    } else if (it.x < left10) {
                        rectWidth = nodeWidth * .40
                        extendSplitPaneDirection = Extend.LEFT
                    } else if (it.x > right10) {
                        minX = right40
                        rectWidth = nodeWidth * .40
                        extendSplitPaneDirection = Extend.RIGHT
                    } else {
                        extendSplitPaneDirection = null
                    }
                } else {
                    extendSplitPaneDirection = null
                }

                val originInScreen = this.localToScreen(minX, minY)
                val originInStackPane = this@DetachableTreePane.screenToLocal(originInScreen)
                overlay.apply {
                    translateX = originInStackPane.x
                    translateY = originInStackPane.y
                    width = rectWidth
                    height = rectHeight
                }
                addOverlay()
                tabPaneOver = this
            }
            it.consume()
        }
        onDragExited = EventHandler {
            removeOverlay()
            if (tabPaneOver === this) tabPaneOver = null
            it.consume()
        }
        onDragDropped = EventHandler {
            val differentNode = it.gestureSource !== this
            val hasTabDragKey = it.dragboard.string == TAB_DRAG_KEY
            if (differentNode && hasTabDragKey) {
                extendSplitPaneDirection?.let { dir ->
                    /* extend the split pane!*/
                    extendSplitPaneTree(dir)
                    extendSplitPaneDirection = null
                } ?: let {
                    /* add to the current tabpane*/
                    val tab = draggingTab!!
                    tab.tabPane.tabs.remove(tab)
                    tabs.add(tab)
                }
                tabPaneOver = null
                it.isDropCompleted = true
            }
            it.consume()
        }
    }

    private fun SplitPane.getAdjacentDividers(item: Node): Pair<Double, Double>? {
        return when (val idx = this.items.indexOf(item)) {
            -1 -> null
            else -> this.getAdjacentDividers(idx)
        }
    }

    private fun SplitPane.getAdjacentDividers(itemIdx: Int): Pair<Double, Double> {
        val prevDiv = dividers.getOrNull(itemIdx - 1)?.position ?: let { 0.0 }
        val postDiv = dividers.getOrNull(itemIdx)?.position ?: let { 1.0 }
        return Pair(prevDiv, postDiv)
    }

    private fun SplitPane.newDividerPosByItemIndex(itemIdx: Int): Double {
        val surroundingDividers = this.getAdjacentDividers(itemIdx)
        return surroundingDividers.first + ((surroundingDividers.second - surroundingDividers.first) / 2.0)
    }


    private fun extendSplitPaneTree(extendDirection: Extend) {
        val tabToMove = draggingTab!!
        val tabPaneToRemoveFrom = tabToMove.tabPane
        /* get splitPane parent of tabPane*/
        (tabPaneOver?.parent?.parent as? SplitPane)?.let {
            tabPaneToRemoveFrom.tabs.remove(tabToMove)
            val tabPane = TabPane(tabToMove).also { tp -> tp.initDraggableTabReceiver() }
            when (it.orientation) {
                Orientation.HORIZONTAL -> {
                    when (extendDirection) {
                        Extend.LEFT -> {
                            it.growItemsResizeDividers(tabPane)
                        }
                        Extend.RIGHT -> {
                            it.growItemsResizeDividers(tabPane, after = true)
                        }
                        Extend.TOP -> {
                            it.swapWithOppositeOrientationSplitPane(tabPane)
                        }
                        Extend.BOTTOM -> {
                            it.swapWithOppositeOrientationSplitPane(tabPane, after = true)
                        }
                    }
                }
                Orientation.VERTICAL -> {
                    when (extendDirection) {
                        Extend.LEFT -> {
                            it.swapWithOppositeOrientationSplitPane(tabPane)
                        }
                        Extend.RIGHT -> {
                            it.swapWithOppositeOrientationSplitPane(tabPane, after = true)
                        }
                        Extend.TOP -> {
                            it.growItemsResizeDividers(tabPane)
                        }
                        Extend.BOTTOM -> {
                            it.growItemsResizeDividers(tabPane, after = true)
                        }
                    }
                }
                null -> {
                }
            }
        }
    }

    private fun SplitPane.growItemsResizeDividers(newItem: Node, after: Boolean = false) {
        val idxTargetTabPane = items.indexOf(tabPaneOver)
        val newDividerPos = newDividerPosByItemIndex(idxTargetTabPane)
        val newDividers = mutableListOf<Double>().apply {
            addAll(dividers.map { d -> d.position })
            add(newDividerPos)
            sort()
        }
        if (after) {
            if (idxTargetTabPane + 1 == items.size) {
                items.add(newItem)
            } else {
                items.add(idxTargetTabPane + 1, newItem)
            }
        } else {
            items.add(idxTargetTabPane, newItem)
        }
        setDividerPositions(*newDividers.toDoubleArray())
    }

    private fun SplitPane.swapWithOppositeOrientationSplitPane(newNode: Node, after: Boolean = false) {
        val replacementVerticalSplitPane = newSplitPaneTreeNode(orientation)
        orientation = if (orientation == Orientation.HORIZONTAL) Orientation.VERTICAL else Orientation.HORIZONTAL

        replacementVerticalSplitPane.items.addAll(items)
        replacementVerticalSplitPane.setDividerPositions(*dividerPositions)

        items.apply {
            clear()
            if (after) {
                add(replacementVerticalSplitPane)
                add(newNode)
            } else {
                add(newNode)
                add(replacementVerticalSplitPane)
            }
        }
        setDividerPositions(.5)

    }

    private fun newSplitPaneTreeNode(orientation: Orientation = Orientation.HORIZONTAL): SplitPane {
        return SplitPane().apply { this.orientation = orientation }
    }

    fun addNode(node: Node, name: String, section: Int = 0) {
        val sections = (children[0] as SplitPane).items
        while (sections.size < section + 1) {
            val newSplitPane = TabPane().also { it.initDraggableTabReceiver() }
            sections += newSplitPane
        }
        (sections[section] as TabPane).tabs += newSourceTab(name, node)
    }
}

open class HVBox @JvmOverloads constructor(vararg node: Node, align: Pos = Pos.CENTER) : HBox() {

    private val vbox = VBox()

    init {
        super.getChildren() += vbox
        alignment = align
        vbox.alignment = align
        node.forEach { vbox.children += it }
//        node?.let { vbox.children += it }
    }

    val items: ObservableList<Node> = vbox.children

    override fun getChildren(): ObservableList<Node> {
        return items
    }
}

fun main() {
    PlatformImpl.startup { }
    PlatformImpl.setImplicitExit(true)
    Platform.runLater {
        val splitPane = DetachableTreePane()
        splitPane.addNode(HVBox(Label("Test")), "Test")
        splitPane.addNode(HVBox(Label("Test1")), "Test1")
        splitPane.addNode(HVBox(Label("Test2")), "Test2", 1)
        splitPane.addNode(HVBox(Label("Test3")), "Test3", 1)
        val scene = Scene(splitPane)
        val stage = Stage()
        stage.scene = scene
        stage.width = 800.0
        stage.height = 600.0
//        ScenicView.show(scene)
        stage.show()
    }
}
