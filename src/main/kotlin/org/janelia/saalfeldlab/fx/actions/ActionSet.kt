package org.janelia.saalfeldlab.fx.actions

import javafx.event.Event
import javafx.event.EventHandler
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Window
import org.janelia.saalfeldlab.fx.event.KeyTracker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import java.util.function.Consumer


/**
 * [ActionSet]s allowed for grouping [Action]s.
 *
 * It is often preferable to group [Action]s based on their similarity, either of use, or implementation.
 * [ActionSet] provides a convenient way to create and manage a group of [Action]
 *
 * For example, you may want your application to change state based on a key press, and to revert on key relase:
 * ```kotlin
 * val node : Node = StackPane()
 * val changeStateActionSet = ActionSet("Change Application State", KeyTracker()) {
 *  KeyEvent.KEY_PRESSED(KeyCode.SPACE) {
 *      filter = true
 *      onAction { App.changeState() }
 *  }
 *  KeyEvent.KEY_RELEASED(KeyCode.SPACE) {
 *      filter = true
 *      onAction { App.changeStateBack() }
 *  }
 * }
 * /* To add all actions */
 * node.installActionSet(changeStateActionSet)
 * /* to remove all actions */
 * node.removeActionSet(changeStateActionSet)
 * ```
 * Above we created an action set with two actions, one for a key press, and one for a key release, which trigger some state change to the applications
 *
 * See [DragActionSet] and [org.janelia.saalfeldlab.fx.ortho.GridResizer] for more examples.
 *
 * @see DragActionSet
 * @see org.janelia.saalfeldlab.fx.ortho.GridResizer
 *
 *
 * @property name of the action set. If an [Action] is part of this [ActionSet] and has no name, this will be used for the [Action]s name as well.
 * @property keyTracker to use to keep track of the key state
 * @constructor creates this [ActionSet]
 *
 * @param apply configuration callback to configure this [ActionSet], and to create [Action]s
 */
open class ActionSet(val name: String, var keyTracker: KeyTracker? = null, apply: (ActionSet.() -> Unit)? = null) {

    @JvmOverloads
    constructor(name: String, keyTracker: KeyTracker? = null, apply: Consumer<ActionSet>?) : this(name, keyTracker, { apply?.accept(this) })

    private val actions = mutableListOf<Action<out Event>>()
    private val actionHandlerMap = mutableMapOf<EventType<Event>, MutableList<EventHandler<Event>>>()
    private val actionFilterMap = mutableMapOf<EventType<Event>, MutableList<EventHandler<Event>>>()
    private val checks = mutableMapOf<EventType<out Event>, MutableList<(Event) -> Boolean>>()

    init {
        apply?.let { it(this) }
    }

    private fun testChecksForEventType(event: Event, eventType: EventType<out Event> = event.eventType): Boolean {
        return (checks[eventType]?.reduce { l, r -> { l(event) && r(event) } }?.invoke(event) ?: true)
    }

    private tailrec fun testChecksForInheritedEventTypes(event: Event, eventType: EventType<out Event>? = event.eventType): Boolean {
        if (eventType == null) return true
        return if (!testChecksForEventType(event, eventType)) false else testChecksForInheritedEventTypes(event, eventType.superType)
    }

    /**
     * Specify a check that will be evaluated for all [Action]s in this [ActionSet] that are triggered by [eventType]
     *
     * @param E type of the [Event] that [check] acts on
     * @param eventType that we are checking against
     * @param check callback to verify if an [Action] in this set is valid for an event
     * @receiver
     */
    @Suppress("UNCHECKED_CAST")
    fun <E : Event> verifyAll(eventType: EventType<E>, check: (E) -> Boolean) {
        checks[eventType]?.add(check as (Event) -> Boolean) ?: let {
            checks[eventType] = mutableListOf(check as (Event) -> Boolean)
        }
    }

    /**
     * Creates an [Action] and adds it to this [ActionSet]
     *
     * @param E the [Event] that the created [Action] will handle
     * @param eventType the [EventType] that the created [Action] will trigger on
     * @param withAction configuration in the created [Action]s scope
     * @return the created and configured [Action]
     */
    fun <E : Event> action(eventType: EventType<E>, withAction: Action<E>.() -> Unit = {}): Action<E> {
        return Action(eventType)
            .also { it.keyTracker = this.keyTracker }
            .apply(withAction)
            .also { addAction(it) }
    }

    /**
     * Add an existing [Action] to this [ActionSet]
     *
     * @param E the [Event] that [action] handles
     * @param action the [Action] to add
     */
    @Suppress("UNCHECKED_CAST")
    fun <E : Event> addAction(action: Action<E>) {
        actions += action

        val handler = ActionSetActionEventHandler(action)

        val eventType = action.eventType as EventType<Event>
        /* Add as filter or handler, depending on action flag */
        val actionMap = if (action.filter) actionFilterMap else actionHandlerMap
        actionMap[eventType]?.let { it += handler } ?: let {
            actionMap[eventType] = mutableListOf(handler)
        }
    }


    /**
     * Add a [KeyAction] created and configured via [withAction]
     *
     * @param eventType to trigger
     * @param withAction a consumer providing the created [KeyAction] for configuration
     * @return the [KeyAction]
     */
    @JvmOverloads
    fun addKeyAction(eventType: EventType<KeyEvent>, withAction: Consumer<KeyAction>? = null): KeyAction {
        return withAction?.let {
            keyAction(eventType) {
                withAction.accept(this)
            }
        } ?: keyAction(eventType)
    }

    /**
     * Create and add a [KeyAction] to the [ActionSet].
     *
     * @param eventType that triggers the [KeyAction]
     * @param withAction callback to configure the [KeyAction] within its scope
     * @return the created [KeyAction]
     */
    @JvmSynthetic
    fun keyAction(eventType: EventType<KeyEvent>, withAction: KeyAction.() -> Unit = {}): KeyAction {
        return KeyAction(eventType)
            .also { it.keyTracker = this.keyTracker }
            .apply(withAction)
            .also { action -> addAction(action) }
    }

    /**
     * Convenience operator to create a [KeyAction] from a [KeyEvent] [EventType] receiver, while specifying the required [KeyCode]s
     *
     * @param withKeysDown [KeyCode]s required to be down
     * @param withAction [KeyAction] configuration callback
     * @return the [KeyAction]
     */
    operator fun EventType<KeyEvent>.invoke(vararg withKeysDown: KeyCode, withAction: KeyAction.() -> Unit): KeyAction {
        return keyAction(this, withAction).apply {
            if (withKeysDown.isNotEmpty()) {
                keysDown(*withKeysDown)
            }
        }
    }

    /**
     * Convenience operator to create a [KeyAction] from a [KeyEvent] [EventType] receiver, while specifying the required keybindings
     *
     * @param keyBindings key binding map to lookup the [keyName] in
     * @param keyName name of the valid key combination in the [keyBindings] map
     * @param withAction [KeyAction] configuration callback
     * @return the [KeyAction]
     */
    operator fun EventType<KeyEvent>.invoke(keyBindings: NamedKeyCombination.CombinationMap, keyName: String, withAction: KeyAction.() -> Unit): KeyAction {
        return keyAction(this, withAction).apply {
            keyMatchesBinding(keyBindings, keyName)
        }
    }

    /**
     *  Extension to create and add a [MouseAction] to this [ActionSet]
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <E : MouseEvent> EventType<E>.invoke(withAction: MouseAction.() -> Unit): MouseAction {
        return mouseAction(this as EventType<MouseEvent>, withAction)
    }

    /**
     *  Extension to create and add a [MouseAction] with required [KeyCode]s held down
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <E : MouseEvent> EventType<E>.invoke(vararg withKeysDown: KeyCode, withAction: MouseAction.() -> Unit): MouseAction {
        return mouseAction(this as EventType<MouseEvent>, withAction).apply {
            if (withKeysDown.isNotEmpty()) keysDown(*withKeysDown)
        }
    }

    /**
     *  Extension to create and add a [MouseAction] configured to accept only a single [withOnlyButtonsDown] mouse press
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <E : MouseEvent> EventType<E>.invoke(vararg withOnlyButtonsDown: MouseButton, withAction: MouseAction.() -> Unit): MouseAction {
        return mouseAction(this as EventType<MouseEvent>, withAction).apply {
            verifyButtonsDown(*withOnlyButtonsDown, exclusive = true)
        }
    }

    /**
     *  Extension to create and add a [MouseAction] configured to trigger on a [MouseButton] trigger, either on [released] or pressed.
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <E : MouseEvent> EventType<E>.invoke(withButtonTrigger: MouseButton, released: Boolean = false, withAction: MouseAction.() -> Unit): MouseAction {
        return mouseAction(this as EventType<MouseEvent>, withAction).apply {
            /* default to exclusive if pressed, and NOT exclusive if released*/
            verifyButtonTrigger(withButtonTrigger, released = released, exclusive = !released)
        }
    }

    /**
     * Create and return an [Action] of subtype [R], based on the reciever [E] [EventType].
     *
     * If [E] is a [KeyEvent], the returned action will be a [KeyAction]
     * If [E] is a [MouseEvent], the returned action will be a [MouseAction]
     * If [E] is neither, the returned action will be an [Action]
     *
     * in any case, the resulting action is configured to require [withKeysDown] to be pressed.
     *
     * @param E the target event type
     * @param R the resultant Action type
     * @param withKeysDown required for resultant action to be valid
     * @param withAction configuration callback in the resultant Actions scope
     * @receiver [EventType] the resultant action will trigger
     * @return the created action of type [R]
     */
    @Suppress("UNCHECKED_CAST")
    inline operator fun <reified E : Event, reified R : Action<E>> EventType<E>.invoke(vararg withKeysDown: KeyCode, noinline withAction: R.() -> Unit): R {
        return when (E::class.java) {
            KeyEvent::class.java -> keyAction(this as EventType<KeyEvent>, withAction as KeyAction.() -> Unit) as R
            MouseEvent::class.java -> mouseAction(this as EventType<MouseEvent>, withAction as MouseAction.() -> Unit) as R
            else -> action(this, withAction as Action<E>.() -> Unit) as R
        }.apply {
            if (withKeysDown.isNotEmpty()) keysDown(*withKeysDown)
        }
    }

    /**
     * Create and return an [Action] of subtype [R], based on the reciever [E] [EventType].
     *
     * If [E] is a [KeyEvent], the returned action will be a [KeyAction]
     * If [E] is a [MouseEvent], the returned action will be a [MouseAction]
     * If [E] is neither, the returned action will be an [Action]
     *
     * @param E the target event type
     * @param R the resultant Action type
     * @param withAction configuration callback in the resultant Actions scope
     * @receiver [EventType] the resultant action will trigger
     * @return the created action of type [R]
     */
    @Suppress("UNCHECKED_CAST")
    inline operator fun <reified E : Event, reified R : Action<E>> EventType<E>.invoke(noinline withAction: R.() -> Unit): R {
        return when (E::class.java) {
            KeyEvent::class.java -> keyAction(this as EventType<KeyEvent>, withAction as KeyAction.() -> Unit) as R
            MouseEvent::class.java -> mouseAction(this as EventType<MouseEvent>, withAction as MouseAction.() -> Unit) as R
            else -> action(this, withAction as Action<E>.() -> Unit) as R
        }
    }

    /**
     * Add a [MouseAction] created and configured via [withAction]
     *
     * @param eventType to trigger
     * @param withAction a consumer providing the created [MouseAction] for configuration
     * @return the [MouseAction]
     */
    @JvmOverloads
    fun addMouseAction(eventType: EventType<MouseEvent>, withAction: Consumer<MouseAction>? = null): MouseAction {
        return withAction?.let {
            mouseAction(eventType) {
                withAction.accept(this)
            }
        } ?: mouseAction(eventType)
    }

    /**
     * Add a [MouseAction] created and configured via [withAction]
     *
     * @param eventType to trigger
     * @param withAction a configuration callback in the created [MouseAction]s scope
     * @return the [MouseAction]
     */
    @JvmSynthetic
    fun mouseAction(eventType: EventType<MouseEvent>, withAction: MouseAction.() -> Unit = {}): MouseAction {
        return MouseAction(eventType)
            .also { it.keyTracker = this.keyTracker }
            .apply(withAction)
            .also { addAction(it) }
    }


    private operator fun <E : Event> invoke(event: E, action: Action<E>) {
        if (preInvokeCheck(action, event)) {
            try {
                if (action(event)) {
                    val logger = if (action.filter) FILTER_LOGGER else ACTION_LOGGER
                    val nameText = action.name?.let { "$name: $it" } ?: name
                    /* Log success and not a filter */
                    logger.trace(" $nameText performed")
                }
            } catch (e: Exception) {
                val logger = if (action.filter) FILTER_LOGGER else ACTION_LOGGER
                val nameText = action.name?.let { "$name: $it" } ?: name
                logger.error("$nameText (${event.eventType} was valid, but failed (${e.localizedMessage})")
                throw e
            }
        }
    }

    /**
     * This check is tested when an [EventType] that is a trigger for an [Action] in this [ActionSet] is detected.
     * In that case, the [ActionSet]s checks are tested prior to testing the [Action] itself.
     * This allows certain checks to apply to all [Action]s in this set, for example.
     *
     * @param E the event that [action] could handle
     * @param action the [Action] to handle [event]
     * @param event to handle
     */
    protected open fun <E : Event> preInvokeCheck(action: Action<E>, event: E) = action.canHandleEvent(event.eventType) && testChecksForInheritedEventTypes(event)


    private inner class ActionSetActionEventHandler(val action: Action<out Event>) : EventHandler<Event> {

        @Suppress("UNCHECKED_CAST")
        override fun handle(event: Event) {
            this@ActionSet(event, action as Action<Event>)
        }

        override fun toString(): String {
            return if (action.name.isNullOrEmpty()) {
                this@ActionSet.name.ifEmpty { super.toString() }
            } else {
                action.name!!
            }
        }

    }

    companion object {
        private val ACTION_LOGGER: Logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass().name)
        private val FILTER_LOGGER: Logger = LoggerFactory.getLogger("${MethodHandles.lookup().lookupClass().name}-Filter")

        /**
         * Install [actionSet] in the receiver [Node]
         *
         * @param actionSet to install
         */
        @JvmStatic
        fun Node.installActionSet(actionSet: ActionSet) {
            actionSet.actionFilterMap.forEach { (eventType, actions) ->
                actions.forEach { action ->
                    addEventFilter(eventType, action)
                }
            }
            actionSet.actionHandlerMap.forEach { (eventType, actions) ->
                actions.forEach { action ->
                    addEventHandler(eventType, action)
                }
            }
        }

        /**
         * Remove [actionSet] from the receiver [Node]. No effect if not installed.
         *
         * @param actionSet to remove
         */
        @JvmStatic
        fun Node.removeActionSet(actionSet: ActionSet) {
            actionSet.actionFilterMap.forEach { (eventType, actions) ->
                actions.forEach { action ->
                    removeEventFilter(eventType, action)
                }
            }
            actionSet.actionHandlerMap.forEach { (eventType, actions) ->
                actions.forEach { action ->
                    removeEventHandler(eventType, action)
                }
            }
        }

        /**
         * Install [actionSet] in the receiver [Window]
         *
         * @param actionSet to install
         */
        @JvmStatic
        fun Window.installActionSet(actionSet: ActionSet) {
            actionSet.actionFilterMap.forEach { (eventType, actions) ->
                actions.forEach { action ->
                    addEventFilter(eventType, action)
                }
            }
            actionSet.actionHandlerMap.forEach { (eventType, actions) ->
                actions.forEach { action ->
                    addEventHandler(eventType, action)
                }
            }
        }

        /**
         * Remove [actionSet] from the receiver [Window]. No effect if not installed.
         *
         * @param actionSet to remove
         */
        @JvmStatic
        fun Window.removeActionSet(actionSet: ActionSet) {
            actionSet.actionFilterMap.forEach { (eventType, actions) ->
                actions.forEach { action ->
                    removeEventFilter(eventType, action)
                }
            }
            actionSet.actionHandlerMap.forEach { (eventType, actions) ->
                actions.forEach { action ->
                    removeEventHandler(eventType, action)
                }
            }
        }
    }
}
