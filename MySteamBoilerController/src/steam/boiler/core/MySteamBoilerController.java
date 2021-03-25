package steam.boiler.core;

import steam.boiler.model.SteamBoilerController;
import steam.boiler.util.Mailbox;
import steam.boiler.util.Mailbox.Message;
import steam.boiler.util.Mailbox.MessageKind;
import steam.boiler.util.MemoryAnnotations;
import steam.boiler.util.SteamBoilerCharacteristics;

/**
 * The controller for the given steam boiler simulation.
 * 
 * @author Matt Romanes
 */
public class MySteamBoilerController implements SteamBoilerController  {

  /*------------------------------------------FIELDS------------------------------------------*/
  /**
   * Captures the various modes in which the controller can operate.
   * 
   * @author David J. Pearce
   * 
   */
  private enum State { 
    /**
     * Controller is waiting until physical units indicate they are ready for initialisation.
     */
    WAITING, 
    /**
     * Controller is operating normally.
     */
    NORMAL, 
    /**
     * Controller is operating while aware of unit failures.
     */
    DEGRADED, 
    /**
     * Controller is operating while aware of level unit failure.
     */
    RESCUE, 
    /**
     * Controller has stopped operating due to an emergency situation.
     */
    EMERGENCY_STOP, 
    /**
     * Controller is in initialisation phase before normal operation.
     */
    INITIALISATION 
  }

  /**
   * Captures the various stages of sending and receiving messages while a unit is broken.
   */
  private enum FailureState { 
    /**
     * Physical unit has no failure.
     */ 
    NO_FAIL, 
    /**
     * Controller has detected unit failure.
     */
    FAIL_DETECTED, 
    /**
     * Controller is waiting for unit to acknowledge failure detection.
     */
    WAITING_FAIL_ACK, 
    /**
     * Controller is waiting for unit to be repaired.
     */
    WAITING_REPAIR 
  }

  /**
   * Indicates the various types of failures that can be observed for all physical units.
   */
  private enum FailureType {  
    /**
     * Physical unit has no failure.
     */
    NO_FAILURE, 
    /**
     * Sensor unit is out of bounds.
     */
    OUT_OF_BOUNDS, 
    /**
     * Unit is stuck (i.e. stuck at a value, stuck open).
     */
    STUCK, 
    /**
     * Water level unit is below the predicted range.
     */
    BELOW_PREDICTED_RANGE, 
    /**
     * Water level unit is above the predicted range.
     */
    ABOVE_PREDICTED_RANGE
  }

  /**
   * Records the configuration for the given boiler problem.
   */
  private SteamBoilerCharacteristics config;

  /**
   * Identifies the current mode in which the controller is operating.
   */
  private State mode;

  /**
   * Identifies the state that should be entered after the initialisation phase 
   *   based on whether some physical units are faulty.
   */
  private State modeAfterInitialisation;

  /**
   * Identifies whether the water level sensor is faulty during normal operation.
   */
  private FailureState waterLevelSensorFailedState;

  /**
   * Identifies whether the steam level sensor is faulty during normal operation.
   */
  private FailureState steamLevelSensorFailedState;

  /**
   * Indicates the observed failure of the water level sensor unit.
   */
  private FailureType waterFailType;

  /**
   * Identifies the current state of the valve, where false = closed & true = open.
   */
  private boolean valveOpen;

  /**
   * A boolean that is used to indicate whether the initialisation phase is finished.
   */
  private boolean initialisationFinished;

  /**
   * A boolean indicating whether the program is ready to begin operation of the boiler system.
   */
  private boolean canStartOperation;

  /**
   * An integer to track whether the water sensor value is the same for consecutive cycles.
   */
  private int waterStuckCounter;

  /**
   * An integer to track whether the steam sensor value is the same for consecutive cycles.
   */
  private int steamStuckCounter;

  /**
   * Tracks number of detected failures (except water level unit) as the units fail/repair.
   */
  private int numberOfFailures;

  /**
   * Holds the number of pumps in the system, between 1 and 6, as set by the configuration.
   */
  private int numberOfPumps;

  /**
   * The maximum number of pumps that the simulation allows.
   */
  private static int maxNumberOfPumps = 6;

  /**
   * A double that is used to hold the previous water level according to the water level sensor.
   */
  private double previousWaterLevel;

  /**
   * Holds the maximum possible water level for the number of pumps that were turned on.
   */
  private double previousMaxWaterPossible;

  /**
   * Holds the minimum possible water level for the number of pumps that were turned on.
   */
  private double previousMinWaterPossible;

  /**
   * A double that is used to hold the previous steam level according to the steam sensor.
   */
  private double previousSteamLevel;

  /**
   * Holds the maximum water level under normal operation, as set by the configuration.
   */
  private double maxNormal;

  /**
   * Holds the minimum water level under normal operation, as set by the configuration.
   */
  private double minNormal;

  /**
   * Holds the maximum water level limit, as set by the configuration.
   */
  private double maxLimit;

  /**
   * Holds the minimum water level limit, as set by the configuration.
   */
  private double minLimit;

  /**
   * Holds the middle water level between the max and min for normal operation.
   */
  private double halfCapacity;

  /**
   * A Message object used to indicate a change of mode.
   * The contents of this Message can be set as needed.
   */
  @MemoryAnnotations.Immortal
  private static final 
      Message changeModeMessage = new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL);

  /**
   * A Message object used to send a message to the physical units (i.e. PROGRAM_READY).
   * The contents of this Message can be set as needed.
   */
  @MemoryAnnotations.Immortal
  private static final Message messageToUnits = new Message(MessageKind.PROGRAM_READY);

  /**
   * A Message object used to toggle the valve unit open or closed.
   */
  @MemoryAnnotations.Immortal
  private static final Message toggleValveMessage = new Message(MessageKind.VALVE);

  /**
   * A Message object to store the incoming water level message from the water level sensor unit.
   * The contents of this Message can be changed every cycle.
   */
  @MemoryAnnotations.Immortal
  private static final Message waterLevelMessage = new Message(MessageKind.LEVEL_v, 0.0);

  /**
   * A Message object to store the incoming steam level message from the steam level sensor unit.
   * The contents of this Message can be changed each cycle.
   */
  @MemoryAnnotations.Immortal
  private static final Message steamLevelMessage = new Message(MessageKind.STEAM_v, 0.0);

  /**
   * A Message object to store outgoing messages related to the water level unit's failure state.
   */
  @MemoryAnnotations.Immortal
  private static final 
      Message outgoingWaterFailureMessage = new Message(MessageKind.LEVEL_FAILURE_DETECTION);

  /**
   * A Message object used to store outgoing messages related to the steam unit's failure state.
   */
  @MemoryAnnotations.Immortal
  private static final 
      Message outgoingSteamFailureMessage = new Message(MessageKind.STEAM_FAILURE_DETECTION);

  /**
   * An array of Message objects used to open corresponding pump units 
   *   (i.e. the Message at index 3 corresponds to pump 4).
   */
  @MemoryAnnotations.Immortal
  private static final Message[] openPumpsMessages = new Message[maxNumberOfPumps];

  /**
   * An array of Message objects used to close corresponding pump units 
   *   (i.e. the Message at index 3 corresponds to pump 4).
   */
  @MemoryAnnotations.Immortal
  private static final Message[] closePumpsMessages = new Message[maxNumberOfPumps];

  /**
   * An array of Message objects used to store the incoming messages about the pump units' states.
   * The contents of the Messages can be changed each cycle.
   */
  @MemoryAnnotations.Immortal
  private static final Message[] pumpStatesMessages = new Message[maxNumberOfPumps];

  /**
   * Used to store the incoming messages about the pump controller units' states.
   * The contents of the Messages can be changed each cycle.
   */
  @MemoryAnnotations.Immortal
  private static final Message[] pumpControllerStatesMessages = new Message[maxNumberOfPumps];

  /**
   * Used to temporarily store incoming messages of a specific type 
   *   until the messages can be handled appropriately.
   */
  @MemoryAnnotations.Immortal
  private static final Message[] temporaryMessageArray = new Message[100];

  /**
   * Used to store outgoing messages related to a pump unit's failure state.
   */
  @MemoryAnnotations.Immortal
  private static final Message[] outgoingPumpFailureMessages = new Message[maxNumberOfPumps];

  /**
   * Used to store outgoing messages related to a pump controller unit's failure state.
   */
  @MemoryAnnotations.Immortal
  private static final 
      Message[] outgoingPumpControllerFailureMessages = new Message[maxNumberOfPumps];

  /**
   * Used to store the potential water level for each configuration of pumps 
   *   for one cycle in NORMAL mode.
   *   (i.e. indices 0 and 1 would be the minimum and maximum levels for zero pumps being turned on,
   *   indices 2 and 3 for 1 pump, etc)
   */
  @MemoryAnnotations.Immortal
  private static final double[] potentialResultWaterLevels = new double[(maxNumberOfPumps * 2) + 2];

  /**
   * Used to indicate whether each pump should be on or off, where on is true and off is false.
   */
  @MemoryAnnotations.Immortal
  private static final boolean[] pumpsOnOffStates = new boolean[maxNumberOfPumps];

  /**
   * Used when calculating which pumps should be on and off during normal/degraded operation.
   */
  @MemoryAnnotations.Immortal
  private static final boolean[] calculateTurningPumpsOn = new boolean[maxNumberOfPumps];

  /**
   * An array used to store which stage of failure recovery the unit is in.
   *   (i.e. the system could have sent the detection message and 
   *   the unit has just sent the acknowledgement message, resulting in the value FAIL_ACK)
   */
  @MemoryAnnotations.Immortal
  private static final FailureState[] pumpsFailedState = new FailureState[maxNumberOfPumps];

  /**
   * An array used to store which stage of failure recovery the unit is in.
   *   (i.e. the system could have sent the detection message and 
   *   the unit has just sent the acknowledgement message, resulting in the value FAIL_ACK)
   */
  @MemoryAnnotations.Immortal
  private static final 
      FailureState[] pumpControllersFailedState = new FailureState[maxNumberOfPumps];

  /**
   * An array used to store the observed failures of the pump units.
   */
  @MemoryAnnotations.Immortal
  private static final FailureType[] pumpFailType = new FailureType[maxNumberOfPumps];

  /**
   * An array used to store the observed failures of the pump controller units.
   */
  @MemoryAnnotations.Immortal
  private static final FailureType[] pumpControllerFailType = new FailureType[maxNumberOfPumps];
  /*------------------------------------------------------------------------------------------*/


  /*--------------------------------------PRIMARY METHODS-------------------------------------*/
  /**
   * Construct a steam boiler controller for a given set of characteristics.
   *
   * @param configuration The boiler characteristics to be used.
   */
  public MySteamBoilerController(SteamBoilerCharacteristics configuration) {
    assert configuration != null;

    this.config = configuration;
    this.mode = State.WAITING;
    this.modeAfterInitialisation = State.NORMAL;
    this.waterLevelSensorFailedState = FailureState.NO_FAIL;
    this.steamLevelSensorFailedState = FailureState.NO_FAIL;
    this.waterFailType = FailureType.NO_FAILURE;

    this.valveOpen = false;
    this.initialisationFinished = false;
    this.canStartOperation = false;

    this.waterStuckCounter = 0;
    this.steamStuckCounter = 0;
    this.numberOfFailures = 0;
    this.numberOfPumps = this.config.getNumberOfPumps();

    this.previousWaterLevel = 0.0;
    this.previousMaxWaterPossible = this.config.getMaximalNormalLevel();
    this.previousMinWaterPossible = this.config.getMinimalNormalLevel();
    this.previousSteamLevel = this.config.getMaximualSteamRate();
    this.maxNormal = this.config.getMaximalNormalLevel();
    this.minNormal = this.config.getMinimalNormalLevel();
    this.maxLimit = this.config.getMaximalLimitLevel();
    this.minLimit = this.config.getMinimalLimitLevel();
    this.halfCapacity = this.minNormal + ((this.maxNormal - this.minNormal) / 2.0);

    setInitialFieldArrayValues();
  }

  /**
   * Process a clock signal which occurs every 5 seconds.
   * This requires reading the set of incoming messages from the physical units
   * and producing a set of output messages which are sent back to them.
   *
   * @param incoming The set of incoming messages from the physical units.
   * @param outgoing Messages generated during the execution of this method should be written here.
   */
  @Override
  public void clock(Mailbox incoming, Mailbox outgoing) {
    assert incoming != null;
    assert incoming.size() >= 4; //Min. number of messages (water, steam, pump, pump controller)
    assert outgoing != null;

    //Extract expected messages from units
    extractCycleMessages(incoming);

    //Check if physical units have indicated their ready to start
    Message programReadyResponse = extractOnlyMatch(MessageKind.PHYSICAL_UNITS_READY, incoming);
    if (programReadyResponse != null && this.mode != State.EMERGENCY_STOP) {
      this.mode = this.modeAfterInitialisation;
    }

    //Check for failures of units
    if (this.mode == State.NORMAL || this.mode == State.DEGRADED || this.mode == State.RESCUE) {
      checkForUnitFailures();
    }

    //Handle operation for this cycle based on the current state
    if (this.mode == State.WAITING) {
      this.previousWaterLevel = waterLevelMessage.getDoubleParameter();
      Message boilerWaiting = extractOnlyMatch(MessageKind.STEAM_BOILER_WAITING, incoming);
      if (boilerWaiting != null) {
        this.mode = State.INITIALISATION;
      }
    }
    if (this.mode == State.INITIALISATION) {
      if (!this.initialisationFinished) {
        initialise(outgoing);
      }

      if (this.initialisationFinished)  {
        messageToUnits.set(MessageKind.PROGRAM_READY);
        outgoing.send(messageToUnits);
      }
    } else if (this.mode == State.NORMAL) {
      operatePumps(outgoing, waterLevelMessage.getDoubleParameter());
    } else if (this.mode == State.DEGRADED) {
      degradedOperation(incoming, outgoing);
    } else if (this.mode == State.RESCUE) {
      rescueOperation(incoming, outgoing);
    }

    handleModeMessage(outgoing);

    //Set for the next cycle
    this.previousWaterLevel = waterLevelMessage.getDoubleParameter();
    this.previousSteamLevel = steamLevelMessage.getDoubleParameter();
  }

  /**
   * Handles all operations associated with initialisation of the system.
   *
   * @param outgoing The mailbox for sending messages to physical units.
   */
  private void initialise(Mailbox outgoing) {
    assert outgoing != null;
    double waterLevel = waterLevelMessage.getDoubleParameter();

    //Check sensors for invalid readings
    if (steamLevelMessage.getDoubleParameter() != 0.0) {
      this.mode = State.EMERGENCY_STOP;
    }
    if (waterLevel < 0.0 || waterLevel > this.config.getCapacity()) {
      this.mode = State.EMERGENCY_STOP;
    }
    if (waterLevel == this.previousWaterLevel) {
      if (this.waterStuckCounter == 2) {
        this.mode = State.EMERGENCY_STOP;
        return;
      }
      this.waterStuckCounter++;
    } else  {
      this.waterStuckCounter = 0;
    }

    //Prepare boiler for normal operation
    if (waterLevel >= this.maxNormal) {
      closeAllPumps(outgoing);
      if (!this.valveOpen) {
        toggleValve(outgoing); //Open
      } else if (this.previousWaterLevel == waterLevel) { //Water isn't draining
        this.mode = State.EMERGENCY_STOP;
        return;
      }
    } else if (waterLevel <= this.minNormal) {
      openAllPumps(outgoing);
      if (this.valveOpen) {
        toggleValve(outgoing); //Close
      }
    } else if (waterLevel < this.maxNormal && waterLevel > this.minNormal) {
      closeAllPumps(outgoing);
      if (this.valveOpen) {
        toggleValve(outgoing); //Close
      }
      this.initialisationFinished = true;
    }
  }

  /**
   * Handles all operations during DEGRADED mode.
   *
   * @param incoming The mailbox for receiving messages from physical units.
   * @param outgoing The mailbox for sending messages to physical units.
   */
  private void degradedOperation(Mailbox incoming, Mailbox outgoing) {
    assert incoming != null;
    assert outgoing != null;

    operatePumps(outgoing, waterLevelMessage.getDoubleParameter());
    if (this.mode == State.EMERGENCY_STOP) {
      return;
    }

    //Handle failure state messages
    handleFailureStateMessages(incoming, outgoing);

    if (this.numberOfFailures == 0) {
      this.mode = State.NORMAL;
    }
  }

  /**
   * Handles all operations during RESCUE mode.
   *
   * @param incoming The mailbox for receiving messages from physical units.
   * @param outgoing The mailbox for sending messages to physical units.
   */
  private void rescueOperation(Mailbox incoming, Mailbox outgoing) {
    assert incoming != null;
    assert outgoing != null;

    double estimatedCurrentWaterLevel;
    if (this.previousWaterLevel < this.halfCapacity) {
      estimatedCurrentWaterLevel = this.previousMinWaterPossible;
    } else  {
      estimatedCurrentWaterLevel = this.previousMaxWaterPossible;
    }

    operatePumps(outgoing, estimatedCurrentWaterLevel);
    if (this.mode == State.EMERGENCY_STOP) {
      return;
    }

    //Handle failure state messages
    handleFailureStateMessages(incoming, outgoing);

    if (this.waterLevelSensorFailedState == FailureState.NO_FAIL) {
      if (this.numberOfFailures > 0) {
        this.mode = State.DEGRADED;
      } else  {
        this.mode = State.NORMAL;
      }
    }
  }

  /**
   * Checks the data from the physical units for unit failures.
   */
  private void checkForUnitFailures() {
    //Check steam sensor
    if (this.steamLevelSensorFailedState == FailureState.NO_FAIL) {
      checkSteamFailure();
    }

    //Check water sensor
    boolean waterSeemsBroken = false;
    if (this.waterLevelSensorFailedState == FailureState.NO_FAIL) {
      checkWaterFailure();
      if (this.mode == State.EMERGENCY_STOP) {
        return;
      }
    }
    if (this.waterFailType != FailureType.NO_FAILURE) {
      waterSeemsBroken = true;
    }

    for (int i = 0; i < this.numberOfPumps; i++) {
      //Check pump i
      boolean pumpSeemsBroken = false;
      if (pumpsFailedState[i] == FailureState.NO_FAIL) {
        checkPumpFailure(i);
      }
      if (pumpFailType[i] != FailureType.NO_FAILURE) {
        pumpSeemsBroken = true;
      }

      //Check controller i
      boolean controllerSeemsBroken = false;
      if (pumpControllersFailedState[i] == FailureState.NO_FAIL) {
        checkControllerFailure(i);
      }
      if (pumpControllerFailType[i] != FailureType.NO_FAILURE) {
        controllerSeemsBroken = true;
      }

      //Use logic to check what is broken, if anything
      handleFailureLogic(waterSeemsBroken, pumpSeemsBroken, controllerSeemsBroken, i);

      //Handle pump i result
      if (pumpsFailedState[i] == FailureState.NO_FAIL) {
        pumpFailType[i] = FailureType.NO_FAILURE;
      }

      //Handle controller i result
      if (pumpControllersFailedState[i] == FailureState.NO_FAIL) {
        pumpControllerFailType[i] = FailureType.NO_FAILURE;
      }
    }

    //Handle water sensor result
    if (this.waterLevelSensorFailedState == FailureState.NO_FAIL) {
      this.waterFailType = FailureType.NO_FAILURE;
    } else  {
      this.mode = State.RESCUE;
    }
  }
  /*------------------------------------------------------------------------------------------*/


  /*-------------------------------------SECONDARY METHODS------------------------------------*/
  /**
   * Handles all operations regarding the pumps during operation of the boiler system.
   *
   * @param outgoing The mailbox for sending messages to physical units.
   * @param waterLevel The current water level value to use in calculations.
   */
  private void operatePumps(Mailbox outgoing, double waterLevel) {
    assert outgoing != null;
    assert waterLevel >= 0.0;
    assert waterLevel <= this.config.getCapacity();

    if (this.canStartOperation) {
      //Estimate best water level for next cycle
      int bestIndex = estimateBestWaterLevel(waterLevel);
      if (bestIndex < 0) {
        bestIndex = 0;
      } else if (bestIndex >= potentialResultWaterLevels.length) {
        bestIndex = potentialResultWaterLevels.length - 1;
      }

      //Check that the best water level result is within permitted bounds
      double min;
      double max;
      if (bestIndex % 2 == 0) {
        min = potentialResultWaterLevels[bestIndex];
        max = potentialResultWaterLevels[bestIndex + 1];
      } else  {
        min = potentialResultWaterLevels[bestIndex - 1];
        max = potentialResultWaterLevels[bestIndex];
      }
      if ((min <= this.minLimit || max >= this.maxLimit)) {
        this.mode = State.EMERGENCY_STOP;
        return;
      }

      //Configure pumps to be on and off as needed
      configurePumps(outgoing, bestIndex);

      //Record the range within which the water level should be next cycle
      if (bestIndex % 2 == 0) {
        this.previousMinWaterPossible = potentialResultWaterLevels[bestIndex];
        this.previousMaxWaterPossible = potentialResultWaterLevels[bestIndex + 1];
      } else  {
        this.previousMinWaterPossible = potentialResultWaterLevels[bestIndex - 1];
        this.previousMaxWaterPossible = potentialResultWaterLevels[bestIndex];
      }
    } else  {
      this.canStartOperation = true;
    }
  }

  /**
   * Calls the utility methods for handling unit fail states.
   *
   * @param incoming The mailbox for receiving messages from physical units.
   * @param outgoing The mailbox for sending messages to physical units.
   */
  private void handleFailureStateMessages(Mailbox incoming, Mailbox outgoing) {
    assert incoming != null;
    assert outgoing != null;

    if (this.waterLevelSensorFailedState != FailureState.NO_FAIL)  {
      handleWaterFailureState(incoming, outgoing);
    }

    if (this.steamLevelSensorFailedState != FailureState.NO_FAIL) {
      handleSteamFailureState(incoming, outgoing);
    }

    for (int i = 0; i < this.numberOfPumps; i++) {
      if (pumpsFailedState[i] != FailureState.NO_FAIL) {
        handlePumpFailureState(incoming, outgoing, i);
      }

      if (pumpControllersFailedState[i] != FailureState.NO_FAIL) {
        handlePumpControllerFailureState(incoming, outgoing, i);
      }
    }
  }

  /**
   * Checks the water level for unit failure.
   */
  private void checkWaterFailure() {
    double waterLevel = waterLevelMessage.getDoubleParameter();

    if (waterLevel == this.previousWaterLevel) {
      if (this.waterStuckCounter == 2) {
        this.waterFailType = FailureType.STUCK;
      } else  {
        this.waterStuckCounter++;
      }
    } else  {
      this.waterStuckCounter = 0;
    }

    if (this.waterFailType != FailureType.STUCK) {
      if (waterLevel < 0.0 || waterLevel > this.config.getCapacity()) {
        this.waterFailType = FailureType.OUT_OF_BOUNDS;
      } else if (waterLevel < this.previousMinWaterPossible - 0.5) {
        this.waterFailType = FailureType.BELOW_PREDICTED_RANGE;
      } else if (waterLevel > this.previousMaxWaterPossible + 0.5) {
        this.waterFailType = FailureType.ABOVE_PREDICTED_RANGE;
      }
    }

    if (this.waterFailType != FailureType.NO_FAILURE 
        && this.steamLevelSensorFailedState != FailureState.NO_FAIL) {
      this.mode = State.EMERGENCY_STOP;
      return;
    }
  }

  /**
   * Checks the steam level for unit failure.
   */
  private void checkSteamFailure() {
    double steamLevel = steamLevelMessage.getDoubleParameter();
    double maxSteam = this.config.getMaximualSteamRate();

    if (steamLevel == this.previousSteamLevel && steamLevel != maxSteam) {
      if (this.steamStuckCounter == 2) {
        this.steamLevelSensorFailedState = FailureState.FAIL_DETECTED;
      } else  {
        this.steamStuckCounter++;
      }
    } else  {
      this.steamStuckCounter = 0;
    }

    if (this.steamLevelSensorFailedState == FailureState.NO_FAIL) {
      if (steamLevel < 0.0 || steamLevel > maxSteam || steamLevel < this.previousSteamLevel) {
        this.steamLevelSensorFailedState = FailureState.FAIL_DETECTED;
        this.numberOfFailures++;
        this.mode = State.DEGRADED;
      }
    }
  }

  /**
   * Checks the pump data for unit failure.
   *
   * @param i The index of the pump unit.
   */
  private void checkPumpFailure(int i) {
    assert i  >= 0;
    assert i < this.numberOfPumps;

    if (pumpsOnOffStates[i] != pumpStatesMessages[i].getBooleanParameter()) {
      if (pumpsFailedState[i] == FailureState.NO_FAIL) {
        pumpFailType[i] = FailureType.STUCK;
      }
    }
  }

  /**
   * Checks the controller data for unit failure.
   *
   * @param i The index of the controller unit.
   */
  private void checkControllerFailure(int i) {
    assert i  >= 0;
    assert i < this.numberOfPumps;

    if (pumpsOnOffStates[i] != pumpControllerStatesMessages[i].getBooleanParameter()) {
      pumpControllerFailType[i] = FailureType.STUCK;
    }
  }

  /**
   * Handle the logic for determining which unit has failed, if any has.
   *
   * @param waterSeemsBroken Whether the water level sensor has given an unexpected value.
   * @param pumpSeemsBroken Whether the pump has given an unexpected value.
   * @param controllerSeemsBroken Whether the pump controller has given an unexpected value.
   * @param i The index of the pump and controller.
   */
  private void handleFailureLogic(boolean waterSeemsBroken, 
      boolean pumpSeemsBroken, boolean controllerSeemsBroken, int i) {
    assert i  >= 0;
    assert i < this.numberOfPumps;

    boolean pumpFailed = false;
    if (!waterSeemsBroken && !pumpSeemsBroken && controllerSeemsBroken) {
      if (pumpControllersFailedState[i] == FailureState.NO_FAIL) {
        this.numberOfFailures++;
        this.mode = State.DEGRADED;
        pumpControllersFailedState[i] = FailureState.FAIL_DETECTED;
      }
    } else if (!waterSeemsBroken && pumpSeemsBroken && !controllerSeemsBroken) {
      pumpFailed = true;
    } else if (waterSeemsBroken && !pumpSeemsBroken && !controllerSeemsBroken 
        && (this.numberOfFailures == 0 
        || (this.numberOfFailures == 1 
        && this.steamLevelSensorFailedState != FailureState.NO_FAIL))) {
      this.waterLevelSensorFailedState = FailureState.FAIL_DETECTED;
    } else if (!waterSeemsBroken && pumpSeemsBroken && controllerSeemsBroken) {
      pumpFailed = true;
    } else if (this.waterFailType == FailureType.ABOVE_PREDICTED_RANGE 
        && pumpSeemsBroken && !pumpsOnOffStates[i] && !controllerSeemsBroken) {
      pumpFailed = true;
    } else if (this.waterFailType == FailureType.BELOW_PREDICTED_RANGE 
        && pumpSeemsBroken && pumpsOnOffStates[i] && !controllerSeemsBroken) {
      pumpFailed = true;
    } else if (this.waterFailType == FailureType.ABOVE_PREDICTED_RANGE 
        && !pumpSeemsBroken && !pumpsOnOffStates[i] && controllerSeemsBroken) {
      pumpFailed = true;
    } else if (this.waterFailType == FailureType.BELOW_PREDICTED_RANGE 
        && !pumpSeemsBroken && pumpsOnOffStates[i] && controllerSeemsBroken) {
      pumpFailed = true;
    } else if (this.waterFailType == FailureType.ABOVE_PREDICTED_RANGE 
        && pumpSeemsBroken && !pumpsOnOffStates[i] && controllerSeemsBroken) {
      pumpFailed = true;
    } else if (this.waterFailType == FailureType.BELOW_PREDICTED_RANGE 
        && pumpSeemsBroken && pumpsOnOffStates[i] && controllerSeemsBroken) {
      pumpFailed = true;
    }

    if (pumpFailed && pumpsFailedState[i] == FailureState.NO_FAIL) {
      this.numberOfFailures++;
      this.mode = State.DEGRADED;
      pumpsFailedState[i] = FailureState.FAIL_DETECTED;
    }
  }
  /*------------------------------------------------------------------------------------------*/


  /*--------------------------------------UTILITY METHODS-------------------------------------*/
  /**
   * Calculates the number of pumps that should be turned on,
   *   then calculates which pumps to be turned on.
   *
   * @param outgoing The mailbox for sending messages to physical units.
   * @param bestIndex The index of the best possible water level estimate 
   *     within potentialResultWaterLevels.
   */
  private void configurePumps(Mailbox outgoing, int bestIndex) {
    assert outgoing != null;
    assert bestIndex >= 0;
    assert bestIndex < potentialResultWaterLevels.length;

    //Calculate the number of pumps to turn on
    int numberPumpsTurningOn = calculateNumberOfPumpsOn(bestIndex);
    if (numberPumpsTurningOn < 0) {
      numberPumpsTurningOn = 0;
    } else if (numberPumpsTurningOn > this.numberOfPumps) {
      numberPumpsTurningOn = this.numberOfPumps;
    }

    //Reset array before use
    for (int i = 0; i < calculateTurningPumpsOn.length; i++) {
      calculateTurningPumpsOn[i] = false;
    }

    //Calculate which pumps should be on
    calculateWhichPumpsOn(numberPumpsTurningOn);

    //Send messages to open and close pumps as needed
    for (int i = 0; i < this.numberOfPumps; i++) {
      if (calculateTurningPumpsOn[i]) {
        openSinglePump(outgoing, i);
      } else  {
        closeSinglePump(outgoing, i);
      }
    }
  }

  /**
   * Calculates how many pumps should be turned on during the current cycle.
   *
   * @param bestIndex The index of the best possible water level estimate 
   *     within potentialResultWaterLevels.
   * @return The number of pumps to be turned on during the current cycle.
   */
  private int calculateNumberOfPumpsOn(int bestIndex) {
    assert bestIndex >= 0;
    assert bestIndex < potentialResultWaterLevels.length;

    int index = 0;
    int numberOfPumpsToTurnOn = 0;
    for (int i = 0; i <= this.numberOfPumps; i++) {
      if (index == bestIndex || index + 1 == bestIndex) {
        numberOfPumpsToTurnOn = i;
        break;
      }
      index += 2;
    }

    return numberOfPumpsToTurnOn;
  }

  /**
   * Calculates which pumps to turn on during the current cycle of operation of the boiler system.
   *
   * @param numberPumpsTurningOn The ideal number of pumps to be turned on.
   */
  private void calculateWhichPumpsOn(int numberPumpsTurningOn) {
    assert numberPumpsTurningOn >= 0;
    assert numberPumpsTurningOn <= this.numberOfPumps;

    int numPumpsOn = 0;
    for (int i = 0; i < this.numberOfPumps; i++) {
      if (numPumpsOn == numberPumpsTurningOn) {
        break;
      }

      if (pumpsFailedState[i] == FailureState.NO_FAIL) {
        calculateTurningPumpsOn[i] = true;
        numPumpsOn++;
      }
    }
  }

  /**
   * Estimates the best possible water level for the next cycle based on current information.
   *
   * @param waterLevel The current water level value to use in calculations.
   * @return The index of the best possible water level estimate within potentialResultWaterLevels.
   */
  private int estimateBestWaterLevel(double waterLevel) {
    assert waterLevel >= 0.0;
    assert waterLevel <= this.config.getCapacity();

    double steamLevel = steamLevelMessage.getDoubleParameter();
    double maxSteam = this.config.getMaximualSteamRate();
    double pumpCapacity = this.config.getPumpCapacity(0);

    //Reset array before use
    for (int i = 0; i < potentialResultWaterLevels.length; i++) {
      potentialResultWaterLevels[i] = -123.456;
    }

    //Calculate the min/max possible water levels for each possible number of pumps
    int index = 0;
    for (int i = 0; i <= this.numberOfPumps; i++) {
      potentialResultWaterLevels[index++] = waterLevel 
          + (5.0 * pumpCapacity * i) - (5.0 * maxSteam); //Min potential for i pumps on
      potentialResultWaterLevels[index++] = waterLevel 
          + (5.0 * pumpCapacity * i) - (5.0 * steamLevel); //Maximum potential
    }

    //Find best possible water level
    int bestIndex = 0;
    double closestDifference = this.config.getCapacity() * 2.0;
    index = 0;
    for (int i = 0; i <= this.numberOfPumps; i++) {
      double currentAverage =  (potentialResultWaterLevels[index + 1] 
          + potentialResultWaterLevels[index]) / 2.0;
      double currentDifference = Math.abs(this.halfCapacity - currentAverage);

      if (currentDifference < closestDifference) {
        bestIndex = index;
        closestDifference = currentDifference;
      }
      index += 2;
    }

    return bestIndex;
  }

  /**
   * Handles the water level sensor failure state by communicating with the unit.
   *
   * @param incoming The mailbox for receiving messages from physical units.
   * @param outgoing The mailbox for sending messages to physical units.
   */
  private void handleWaterFailureState(Mailbox incoming, Mailbox outgoing) {
    assert incoming != null;
    assert outgoing != null;

    if (this.waterLevelSensorFailedState == FailureState.FAIL_DETECTED) {
      outgoingWaterFailureMessage.set(MessageKind.LEVEL_FAILURE_DETECTION);
      outgoing.send(outgoingWaterFailureMessage);
      this.waterLevelSensorFailedState = FailureState.WAITING_FAIL_ACK;
    } else if (this.waterLevelSensorFailedState == FailureState.WAITING_FAIL_ACK) {
      Message failureAck = extractOnlyMatch(MessageKind.LEVEL_FAILURE_ACKNOWLEDGEMENT, incoming);
      if (failureAck != null) {
        this.waterLevelSensorFailedState = FailureState.WAITING_REPAIR;
      }
    } else if (this.waterLevelSensorFailedState == FailureState.WAITING_REPAIR) {
      Message repaired = extractOnlyMatch(MessageKind.LEVEL_REPAIRED, incoming);
      if (repaired != null) {
        outgoingWaterFailureMessage.set(MessageKind.LEVEL_REPAIRED_ACKNOWLEDGEMENT);
        outgoing.send(outgoingWaterFailureMessage);

        this.waterLevelSensorFailedState = FailureState.NO_FAIL;
        this.waterStuckCounter = 0;
        this.waterFailType = FailureType.NO_FAILURE;
      }
    }
  }

  /**
   * Handles the steam level sensor failure state by communicating with the unit.
   *
   * @param incoming The mailbox for receiving messages from physical units.
   * @param outgoing The mailbox for sending messages to physical units.
   */
  private void handleSteamFailureState(Mailbox incoming, Mailbox outgoing) {
    assert incoming != null;
    assert outgoing != null;

    if (this.steamLevelSensorFailedState == FailureState.FAIL_DETECTED) {
      outgoingSteamFailureMessage.set(MessageKind.STEAM_FAILURE_DETECTION);
      outgoing.send(outgoingSteamFailureMessage);
      this.steamLevelSensorFailedState = FailureState.WAITING_FAIL_ACK;
    } else if (this.steamLevelSensorFailedState == FailureState.WAITING_FAIL_ACK) {
      Message failureAck = 
          extractOnlyMatch(MessageKind.STEAM_OUTCOME_FAILURE_ACKNOWLEDGEMENT, incoming);
      if (failureAck != null) {
        this.steamLevelSensorFailedState = FailureState.WAITING_REPAIR;
      }
    } else if (this.steamLevelSensorFailedState == FailureState.WAITING_REPAIR) {
      Message repaired = extractOnlyMatch(MessageKind.STEAM_REPAIRED, incoming);
      if (repaired != null) {
        outgoingSteamFailureMessage.set(MessageKind.STEAM_REPAIRED_ACKNOWLEDGEMENT);
        outgoing.send(outgoingSteamFailureMessage);

        this.steamLevelSensorFailedState = FailureState.NO_FAIL;
        this.numberOfFailures--;
        this.steamStuckCounter = 0;
      }
    }
  }

  /**
   * Handles a pump failure state by communicating with the unit.
   *
   * @param incoming The mailbox for receiving messages from physical units.
   * @param outgoing The mailbox for sending messages to physical units.
   * @param i The index of the pump.
   */
  private void handlePumpFailureState(Mailbox incoming, Mailbox outgoing, int i) {
    assert incoming != null;
    assert outgoing != null;
    assert i  >= 0;
    assert i < this.numberOfPumps;

    if (pumpsFailedState[i] == FailureState.FAIL_DETECTED) {
      outgoingPumpFailureMessages[i].set(MessageKind.PUMP_FAILURE_DETECTION_n, i);
      outgoing.send(outgoingPumpFailureMessages[i]);
      pumpsFailedState[i] = FailureState.WAITING_FAIL_ACK;
    } else if (pumpsFailedState[i] == FailureState.WAITING_FAIL_ACK) {
      extractAllMatches(MessageKind.PUMP_FAILURE_ACKNOWLEDGEMENT_n, incoming);
      for (int n = 0; n < temporaryMessageArray.length; n++) {
        if (temporaryMessageArray[n] == null) {
          break;
        } else if (temporaryMessageArray[n].getIntegerParameter() == i) {
          pumpsFailedState[i] = FailureState.WAITING_REPAIR;
          break;
        }
      }
    } else if (pumpsFailedState[i] == FailureState.WAITING_REPAIR) {
      extractAllMatches(MessageKind.PUMP_REPAIRED_n, incoming);
      for (int n = 0; n < temporaryMessageArray.length; n++) {
        if (temporaryMessageArray[n] == null) {
          break;
        } else if (temporaryMessageArray[n].getIntegerParameter() == i) {
          outgoingPumpFailureMessages[i].set(MessageKind.PUMP_REPAIRED_ACKNOWLEDGEMENT_n, i);
          outgoing.send(outgoingPumpFailureMessages[i]);

          closeSinglePump(outgoing, i);

          pumpsFailedState[i] = FailureState.NO_FAIL;
          this.numberOfFailures--;
          pumpFailType[i] = FailureType.NO_FAILURE;
          break;
        }
      }
    }
  }

  /**
   * Handles a pump controller failure state by communicating with the unit.
   *
   * @param incoming The mailbox for receiving messages from physical units.
   * @param outgoing The mailbox for sending messages to physical units.
   * @param i The index of the pump controller.
   */
  private void handlePumpControllerFailureState(Mailbox incoming, Mailbox outgoing, int i) {
    assert incoming != null;
    assert outgoing != null;
    assert i  >= 0;
    assert i < this.numberOfPumps;

    if (pumpControllersFailedState[i] == FailureState.FAIL_DETECTED) {
      outgoingPumpControllerFailureMessages[i].set(MessageKind.PUMP_CONTROL_FAILURE_DETECTION_n, i);
      outgoing.send(outgoingPumpControllerFailureMessages[i]);
      pumpControllersFailedState[i] = FailureState.WAITING_FAIL_ACK;
    } else if (pumpControllersFailedState[i] == FailureState.WAITING_FAIL_ACK) {
      extractAllMatches(MessageKind.PUMP_CONTROL_FAILURE_ACKNOWLEDGEMENT_n, incoming);
      for (int n = 0; n < temporaryMessageArray.length; n++) {
        if (temporaryMessageArray[n] == null) {
          break;
        } else if (temporaryMessageArray[n].getIntegerParameter() == i) {
          pumpControllersFailedState[i] = FailureState.WAITING_REPAIR;
          break;
        }
      } 
    } else if (pumpControllersFailedState[i] == FailureState.WAITING_REPAIR) {
      extractAllMatches(MessageKind.PUMP_CONTROL_REPAIRED_n, incoming);
      for (int n = 0; n < temporaryMessageArray.length; n++) {
        if (temporaryMessageArray[n] == null) {
          break;
        } else if (temporaryMessageArray[n].getIntegerParameter() == i) {
          outgoingPumpControllerFailureMessages[i].set(
              MessageKind.PUMP_CONTROL_REPAIRED_ACKNOWLEDGEMENT_n, i);
          outgoing.send(outgoingPumpControllerFailureMessages[i]);

          pumpControllersFailedState[i] = FailureState.NO_FAIL;
          this.numberOfFailures--;
          pumpControllerFailType[i] = FailureType.NO_FAILURE;
          break;
        }
      }
    }
  }

  /**
   * Toggles the current state of the valve (i.e. if it is open, it becomes closed).
   *
   * @param outgoing The mailbox to send the required message to the valve unit.
   */
  private void toggleValve(Mailbox outgoing) {
    assert outgoing != null;

    outgoing.send(toggleValveMessage);
    this.valveOpen = !this.valveOpen;
  }

  /**
   * Opens all pumps at once.
   *
   * @param outgoing The mailbox to send the required message to the valve unit.
   */
  private void openAllPumps(Mailbox outgoing) {
    assert outgoing != null;

    for (int i = 0; i < this.numberOfPumps; i++) {
      if (!pumpsOnOffStates[i]) {
        outgoing.send(openPumpsMessages[i]);
        pumpsOnOffStates[i] = true;
      }
    }
  }

  /**
   * Opens one pump.
   *
   * @param outgoing The mailbox to send the required message to the valve unit.
   * @param n The number of the pump unit to be opened.
   */
  private void openSinglePump(Mailbox outgoing, int n) {
    assert outgoing != null;
    assert n >= 0;
    assert n < this.numberOfPumps;

    if (!pumpsOnOffStates[n]) {
      outgoing.send(openPumpsMessages[n]);
      pumpsOnOffStates[n] = true;
    }
  }

  /**
   * Closes all pumps at once.
   *
   * @param outgoing The mailbox to send the required message to the valve unit.
   */
  private void closeAllPumps(Mailbox outgoing) {
    assert outgoing != null;

    for (int i = 0; i < this.numberOfPumps; i++) {
      if (pumpsOnOffStates[i]) {
        outgoing.send(closePumpsMessages[i]);
        pumpsOnOffStates[i] = false;
      }
    }
  }

  /**
   * Closes one pump.
   *
   * @param outgoing The mailbox to send the required message to the valve unit.
   * @param n The number of the pump unit to closed.
   */
  private void closeSinglePump(Mailbox outgoing, int n) {
    assert outgoing != null;
    assert n >= 0;
    assert n < this.numberOfPumps;

    if (pumpsOnOffStates[n]) {
      outgoing.send(closePumpsMessages[n]);
      pumpsOnOffStates[n] = false;
    }
  }

  /**
   * Check whether there was a transmission failure.
   * This occurs when either a message is missing or the value inside the message is nonsensical.
   * 
   * @param waterLevel The message from the water sensor unit.
   * @param steamLevel The message from the steam sensor unit.
   * @param pumps The array of messages from the pump units.
   * @param pumpControllers The array of messages from the pump controller units.
   * @return True if a transmission failure has occurred.
   */
  private boolean checkTransmissionFailure(Message waterLevel, Message steamLevel, 
      Message[] pumps, Message[] pumpControllers) {
    assert pumps != null;
    assert pumps.length != 0;
    assert pumps.length <= maxNumberOfPumps;
    assert pumpControllers != null;
    assert pumpControllers.length != 0;
    assert pumpControllers.length <= maxNumberOfPumps;

    //Check that water level message was received correctly
    if (waterLevel == null) {
      return true;
    }

    //Check that steam level message was received correctly
    if (steamLevel == null) {
      return true;
    }

    //Check that all pump unit state messages were received correctly
    for (int i = 0; i < this.numberOfPumps; i++) {
      if (pumps[i] == null) {
        return true;
      }
    }

    //Check that all pump controller unit state messages were received correctly
    for (int i = 0; i < this.numberOfPumps; i++) {
      if (pumpControllers[i] == null) {
        return true;
      }
    }

    //No transmission error
    return false;
  }

  /**
   * Find and extract the required messages for a cycle.
   *
   * @param incoming The mailbox to search through.
   */
  private void extractCycleMessages(Mailbox incoming) {
    extractAllMatches(MessageKind.PUMP_STATE_n_b, incoming);
    for (int i = 0; i < this.numberOfPumps; i++) {
      pumpStatesMessages[i] = temporaryMessageArray[i];
    }
    extractAllMatches(MessageKind.PUMP_CONTROL_STATE_n_b, incoming);
    for (int i = 0; i < this.numberOfPumps; i++) {
      pumpControllerStatesMessages[i] = temporaryMessageArray[i];
    }
    Message levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
    Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);

    //Check if transmission failure occurred
    if (checkTransmissionFailure(levelMessage, steamMessage, 
        pumpStatesMessages, pumpControllerStatesMessages)) {
      this.mode = State.EMERGENCY_STOP; //Something went wrong with message transmission
    } else  {
      waterLevelMessage.set(levelMessage.getKind(), levelMessage.getDoubleParameter());
      steamLevelMessage.set(steamMessage.getKind(), steamMessage.getDoubleParameter());
    }
  }

  /**
   * Find and extract a message of a given kind in a mailbox. This must the only
   * match in the mailbox, else <code>null</code> is returned.
   *
   * @param kind     The kind of message to look for.
   * @param incoming The mailbox to search through.
   * @return The matching message, or <code>null</code> if there was not exactly
   *         one match.
   */
  private static Message extractOnlyMatch(MessageKind kind, Mailbox incoming)  {
    assert kind != null;
    assert incoming != null;
    assert incoming.size() > 0;

    Message match = null;
    for (int i = 0; i != incoming.size(); ++i)  {
      Message ith = incoming.read(i);
      if (ith.getKind() == kind)  {
        if (match == null)  {
          match = ith;
        } else  {
          // This indicates that we matched more than one message of the given kind.
          return null;
        }
      }
    }
    return match;
  }

  /**
   * Find and extract all messages of a given kind.
   *
   * @param kind     The kind of message to look for.
   * @param incoming The mailbox to search through.
   */
  private static void extractAllMatches(MessageKind kind, Mailbox incoming)  {
    assert kind != null;
    assert incoming != null;
    assert incoming.size() > 0;

    //Reset array
    for (int i = 0; i < temporaryMessageArray.length; i++) {
      temporaryMessageArray[i] = null;
    }

    int count = 0;
    // Count the number of matches
    for (int i = 0; i != incoming.size(); ++i)  {
      Message ith = incoming.read(i);
      if (ith.getKind() == kind)  {
        count = count + 1;
      }
    }
    // Now, construct resulting array
    int index = 0;
    for (int i = 0; i != incoming.size(); ++i)  {
      Message ith = incoming.read(i);
      if (ith.getKind() == kind)  {
        temporaryMessageArray[index++] = ith;
      }
    }
  }

  /**
   * Handles updating the physical units of any state change at the end of each cycle.
   *
   * @param outgoing The mailbox for sending messages to physical units.
   */
  private void handleModeMessage(Mailbox outgoing) {
    assert outgoing != null;

    if (this.mode == State.EMERGENCY_STOP) {
      changeModeMessage.set(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP);
      outgoing.send(changeModeMessage);
    } else if (this.mode == State.NORMAL) {
      changeModeMessage.set(MessageKind.MODE_m, Mailbox.Mode.NORMAL);
      outgoing.send(changeModeMessage);
    } else if (this.mode == State.DEGRADED) {
      changeModeMessage.set(MessageKind.MODE_m, Mailbox.Mode.DEGRADED);
      outgoing.send(changeModeMessage);
    } else if (this.mode == State.RESCUE) {
      changeModeMessage.set(MessageKind.MODE_m, Mailbox.Mode.RESCUE);
      outgoing.send(changeModeMessage);
    } else if (this.mode == State.WAITING || this.mode == State.INITIALISATION) {
      changeModeMessage.set(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION);
      outgoing.send(changeModeMessage);
    }
  }

  /**
   * Utilised by constructor to set the initial values within the field arrays.
   */
  @MemoryAnnotations.Initialisation
  private static void setInitialFieldArrayValues() {
    for (int i = 0; i < maxNumberOfPumps; i++) {
      openPumpsMessages[i] = new Message(MessageKind.OPEN_PUMP_n, i);
      closePumpsMessages[i] = new Message(MessageKind.CLOSE_PUMP_n, i);

      pumpStatesMessages[i] = new Message(MessageKind.PUMP_STATE_n_b, i, false);
      pumpControllerStatesMessages[i] = new Message(MessageKind.PUMP_CONTROL_STATE_n_b, i, false);

      outgoingPumpFailureMessages[i] = new Message(MessageKind.PUMP_FAILURE_DETECTION_n, i);
      outgoingPumpControllerFailureMessages[i] = new Message(
          MessageKind.PUMP_CONTROL_FAILURE_DETECTION_n, i);

      pumpsOnOffStates[i] = false;

      calculateTurningPumpsOn[i] = false;

      pumpsFailedState[i] = FailureState.NO_FAIL;
      pumpControllersFailedState[i] = FailureState.NO_FAIL;

      pumpFailType[i] = FailureType.NO_FAILURE;
      pumpControllerFailType[i] = FailureType.NO_FAILURE;
    }

    for (int i = 0; i < 100; i++) {
      temporaryMessageArray[i] = new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION);
    }

    for (int i = 0; i < ((maxNumberOfPumps * 2) + 2); i++) {
      potentialResultWaterLevels[i] = 0.0;
    }
  }

  /**
   * The current operating state is displayed in the simulation window.
   *
   * @return The current state that the Controller is operating in.
   */
  @Override
  public String getStatusMessage()  {
    return this.mode.toString();
  }
  
}
