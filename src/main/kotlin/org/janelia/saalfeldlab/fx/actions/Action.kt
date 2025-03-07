package org.janelia.saalfeldlab.fx.actions

import javafx.event.Event
import javafx.event.EventHandler
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import org.janelia.saalfeldlab.fx.actions.Action.Companion.action
import org.janelia.saalfeldlab.fx.actions.Action.Companion.installAction
import org.janelia.saalfeldlab.fx.actions.Action.Companion.onAction
import org.janelia.saalfeldlab.fx.actions.Action.Companion.removeAction
import org.janelia.saalfeldlab.fx.event.KeyTracker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import java.util.function.Consumer

/**
 * An [Action] is an event handler, with associated state to properly trigger or not based on the state of the application, and the event.
 *
 * [Action]s can trigger, or not, based on:
 *  - keys pressed
 *  - application disabled state
 *  - arbitrary checks, provided by the developer
 *
 *  [Action]s can also have a specified callback to handle exceptions thrown while executing.
 *
 *  An [Action] typically should be used via an [ActionSet], but they can be used standalone as well, via the [EventType.action] extension function.
 *  [Action]s need to be installed into a node as either a filter or handler. This is handled automatically based on the state of
 *  the [filter] flag. If [filter] is true, then the [Action] will be installed as a filter. [Action]s should not be added as an event
 *  handler manually, but rather by using the [Node].[installAction] extension function provided. [Node].[removeAction] should be used
 *  to remove the [Action] from the node.
 *
 *  A typical construction and usage of an [Action] looks like the following:
 *
 *  ```kotlin
 *  val node : Node = StackPane()
 *  val someExternalCheck : () -> Boolean = { true }
 *  val someOtherExternalCheck : () -> Boolean = { true }
 *
 *  val helloWorldAction = KeyEvent.KEY_PRESSED.action {
 *      keysExclusive = true
 *      keysDown(KeyCode.CONTROL, KeyCode.SPACE)
 *      filter = true
 *      consume = false
 *      verify { someExternalCheck() }
 *      verify { someOtherExternalCheck() }
 *      onAction {
 *          println("Hello World!")
 *      }
 *      handleException {
 *          println("Something went wrong!")
 *      }
 *  }
 *
 *  node.installAction(helloWorldAction)
 *  /* To Remove: */
 *  node.removeAction(helloWorldAction)
 *  ```
 *  When `node` received a [KeyEvent.KEY_PRESSED] event, it will attempt to trigger this action. The `helloWorldAction` will
 *  first verify that the key state of the event is correct, that is both [KeyCode.CONTROL] and [KeyCode.SPACE] are depreseed.
 *  Then the arbitrary [verify] calls will be tested. If all checks are `true` then the [onAction] call will proceed. If an exceptionb occurs
 *  [handleException] will be triggered.
 *
 *  If  you just want the event handler, with no filtering, you can quickly create one for a given event type with the [EventType.onAction] extension
 *  function, which will return a simple [EventHandler] than can be manually added to a [Node].
 *
 *
 * @param E the [Event] we want to handle
 * @property eventType the [EventType] we want to trigger the action on
 * @constructor Creates an [Action]; Should only directly be used internally. Otherwise, use the extension methods of [ActionSet] or [EventType] instead.
 */
open class Action<E : Event>(val eventType: EventType<E>) {


    /**
     * Name of the [Action]. Used as part of `toString` for the resulting [EventHandler]
     */
    var name: String? = null

    /**
     *  Indicates whether the event should be consumed if this Action is triggered
     *  */
    var consume = true

    /**
     *  if false, this action is an event handler, if true, this is an event filter
     *  */
    var filter = false

    /**
     *  if true, all keys are required, and no other keys are allowed. If false, other keys are allowed
     *  */
    var keysExclusive = false

    /**
     * Key tracker used to [verifyKeys]. If not provided, all calls to [verifyKeys] will return `false`, UNLESS [ignoreKeys] was called.
     */
    var keyTracker: KeyTracker? = null

    /**
     * Not used internally. Provided for developers as a way to override the results of [ActionSet.preInvokeCheck]
     */
    var triggerIfDisabled = false /* Can be utilized by the called to block certain actions when the state of the application is disabled. */

    private var keysDown: List<KeyCode>? = listOf()

    private val checks = mutableListOf<(E) -> Boolean>()

    private var exceptionHandler: ((Exception) -> Unit)? = null

    private var action: (E) -> Unit = {}

    private var onException: (Exception) -> Unit = {}

    /**
     * Lazy reference to the [ActionEventHandler] created by this [Action]
     */
    val handler: EventHandler<E> by lazy { ActionEventHandler() }

    /**
     * Verify the check prior to triggering action
     *
     * @param check callback to verify the event of type  [E] is valid for this [Action].
     * @receiver
     */
    fun verify(check: (E) -> Boolean) {
        checks.add(check)
    }

    internal tailrec fun canHandleEvent(checkEventType: EventType<*>?): Boolean {
        checkEventType ?: return false
        return if (eventType == checkEventType) true else canHandleEvent(checkEventType.superType)
    }

    /**
     * Is valid if the keystate, disabled check, and [verify]-provided checks all pass for the given [event]
     *
     * @param event to check if is a valid trigger for this [Action]
     * @return true if [Action] trigger should proceed.
     */
    fun isValid(event: E): Boolean {
        return canHandleEvent(event.eventType) && verifyKeys(event) && testChecks(event)
    }

    /**
     * Verify that the expected key state matches the event.
     *
     * A call to [ignoreKeys] will cause this to always return true
     * An Action created with no keytracker will always fail, unless [ignoreKeys] is called.
     * Otherwise, the keytracker must match the expected [Action]'s [keysDown] and [keysExclusive]state
     *
     * @param event that we are verifying the keystate against
     * @return true onliy if keytracker keystate matches the event's keystate, or if [ignoreKeys] was called
     */
    protected open fun verifyKeys(event: E): Boolean {
        // only null if set intentionally, which is done if we don't care about keys
        if (keysDown == null) return true

        /* Three conditions to check;
         *  - If we expect no keys to be down
         *  - If ONLY the keys we expect are down
         *  - If AT LEAST the keys we expect are down  */
        return keyTracker?.run {
            when {
                keysDown!!.isEmpty() -> noKeysActive()
                keysExclusive -> areOnlyTheseKeysDown(*keysDown!!.toTypedArray())
                else -> areKeysDown(*keysDown!!.toTypedArray())
            }
        } ?: false

    }

    private fun testChecks(event: E): Boolean {
        return checks.isEmpty() || checks.reduce { l, r -> { l(event) && r(event) } }.invoke(event)
    }

    /**
     * Sets [keysDown] to null. This is used to indicate that this [Action] doesn't care about the state of keys.
     * Causes [verifyKeys] to always return true.
     *
     */
    fun ignoreKeys() {
        keysDown = null
    }

    /**
     * Specify the callback when the [Action] is valid
     *
     * @param handle callback when the [Action] is valid
     */
    @JvmSynthetic
    fun onAction(handle: (E) -> Unit) {
        action = handle
    }

    /**
     * Specify the callback when the [Action] is valid
     *
     * @param handle callback when the [Action] is valid
     */
    fun onAction(handle: Consumer<E>) {
        action = { handle.accept(it) }
    }

    /**
     * Specify a callback to be triggered if an [Exception] is thrown during event handling
     *
     * @param handler callback when an exception occurse
     */
    @JvmSynthetic
    fun handleException(handler: (Exception) -> Unit) {
        exceptionHandler = handler
    }


    /**
     * Specify a callback to be triggered if an [Exception] is thrown during event handling
     *
     * @param handler callback when an exception occurse
     */
    fun handleException(handler: Consumer<Exception>) {
        exceptionHandler = { handler.accept(it) }
    }

    /**
     * Specify [KeyCode]s required to be pressed for [Action] to be valid.
     *
     * @param keyCodes to require for [Action] to trigger
     * @param exclusive if true, requires that no other keys are pressed, other than specified by [keyCodes].
     */
    @JvmOverloads
    fun keysDown(vararg keyCodes: KeyCode, exclusive: Boolean = true) {
        keysExclusive = exclusive
        keysDown = listOf(*keyCodes)
    }


    /**
     * Ensures the [Action] will only be triggered if no keys are pressed.
     * Equiavalent to [keysDown] with no paramters
     *
     */
    fun verifyNoKeysDown() {
        keysDown()
    }


    operator fun invoke(event: E): Boolean {
        return if (!event.isConsumed && isValid(event)) {
            try {
                action(event)
            } catch (e: Exception) {
                exceptionHandler?.invoke(e) ?: throw e
            }
            if (consume) {
                event.consume()
            }
            true
        } else false
    }

    /**
     * Custom EventHandler used only to provide the name of the Action and delegate the [Action.invoke] as an [EventHandler]
     *
     * @constructor Create Action event handler
     */
    private inner class ActionEventHandler : EventHandler<E> {
        override fun handle(event: E) {
            this@Action(event)
        }

        override fun toString(): String {
            return name?.let {
                "${ActionEventHandler::class.java.simpleName}: $it"
            } ?: super.toString()
        }
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass().name)


        /**
         * Creates an [Action] that will be triggered by [EventType] [T] and hande [Event]s of type [E].
         *
         * @param T the [EventType] to trigger this [Action]
         * @param E the [Event] that this [Action] will handle
         * @param action a callback inside the resultant [Action]s scope, to allow for configuration.
         * @receiver the [EventType] that triggers this [Action]
         * @return the [Action] created
         */
        @Suppress("UNCHECKED_CAST")
        inline fun <reified T : EventType<E>, E : Event> T.action(noinline action: Action<E>.() -> Unit) =
            when (T::class.java) {
                KeyEvent::class.java -> KeyAction(this as EventType<KeyEvent>)
                MouseEvent::class.java -> MouseAction(this as EventType<MouseEvent>)
                else -> Action(this)
            }.apply { (this as Action<E>).action() } as Action<E>

        /**
         * Provides a quick way to create an [Action] that has no configuration, other than a [name] and [action] callback.
         * This is useful when you want a simply [EventHandler] trigger.
         *
         * @param T the [EventType] to trigger this [Action]
         * @param E the [Event] that this [Action] will handle
         * @param name of the [Action]
         * @param onAction a callback inside the resultant [Action]s scope, to allow for configuration.
         * @receiver the [EventType] that triggers this [Action]
         * @return the [Action] the was created
         */
        @Suppress("UNCHECKED_CAST")
        inline fun <reified T : EventType<E>, E : Event> T.onAction(name: String? = null, noinline onAction: (E) -> Unit): Action<E> =
            when (T::class.java) {
                KeyEvent::class.java -> KeyAction(this as EventType<KeyEvent>)
                MouseEvent::class.java -> MouseAction(this as EventType<MouseEvent>)
                else -> Action(this)
            }.apply {
                (this as Action<E>).apply {
                    this.name = name
                    onAction { onAction(it) }
                }
            } as Action<E>


        /**
         * Install this [Action] as an [EventHandler] into this [Node]
         *
         * @param E the [Event] [action] should handle
         * @param action the [Action] to install to this [Node]
         */
        @JvmStatic
        fun <E : Event> Node.installAction(action: Action<E>) {
            if (action.filter) {
                addEventFilter(action.eventType, action.handler)
            } else {
                addEventHandler(action.eventType, action.handler)
            }
        }

        /**
         * Remove this [Action]s [EventHandler] from this [Node]. No effect if not installed.
         *
         * @param E the [Event] [action] should handle
         * @param action the [Action] to remove to this [Node]
         */
        @JvmStatic
        fun <E : Event> Node.removeAction(action: Action<E>) {
            if (action.filter) {
                removeEventFilter(action.eventType, action.handler)
            } else {
                removeEventHandler(action.eventType, action.handler)
            }
        }
    }
}
