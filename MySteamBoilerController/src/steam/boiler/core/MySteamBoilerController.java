package steam.boiler.core;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import steam.boiler.model.SteamBoilerController;
import steam.boiler.util.Mailbox;
import steam.boiler.util.MemoryAnnotations;
import steam.boiler.util.SteamBoilerCharacteristics;
import steam.boiler.util.Mailbox.Message;
import steam.boiler.util.Mailbox.MessageKind;

import java.util.ArrayList;
import java.util.List;


/**
 * The main steam boiler class.
 * @author Matt Romanes
 *
 */
public class MySteamBoilerController implements SteamBoilerController {

	/**
	 * Captures the various modes in which the controller can operate.
	 *
	 * @author David J. Pearce
	 *
	 */
	private enum State {
		WAITING, READY, NORMAL, DEGRADED, RESCUE, EMERGENCY_STOP
	}

	/**
	 * Records the configuration characteristics for the given boiler problem.
	 */
	private final SteamBoilerCharacteristics configuration;

	/**
	 * Identifies the current mode in which the controller is operating.
	 */
	private State mode = State.WAITING;
	private State prevRescueMode = State.WAITING;
	private State prevDegradedMode = State.WAITING;
	private Mailbox outgoing;
	private Mailbox incoming;
	private Message levelMessage;
	private Message steamMessage;
	private Message[] pumpStateMessages;
	private Message[] pumpControlStateMessages;
	private boolean openValve = false;
	private double waterLevel = 0;
	private double rescueWaterEstimate = 0;
	private double steamLevel = 0.0;
	private int brokenPumpNo = -1;
	private boolean stuck = false;

	private List<@NonNull Boolean> onOffPumps = new ArrayList<>();
	private ArrayList<@NonNull Double> middlePoints = new ArrayList<>();


	/**
	 * Construct a steam boiler controller for a given set of characteristics.
	 *
	 * @param configuration
	 *          The boiler characteristics to be used.
	 */
	public MySteamBoilerController(SteamBoilerCharacteristics configuration) {
		this.configuration = configuration;
		this.pumpStateMessages = new Message[this.configuration.getNumberOfPumps() + 1];
		this.pumpControlStateMessages = new Message[this.configuration.getNumberOfPumps() + 1];

		pumpListInitialisation();
	}
	
	/**
	 * Initialises lists used in program
	 */
	@MemoryAnnotations.Initialisation
	public void pumpListInitialisation() {
		for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
			this.onOffPumps.add(false);
		}
		for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
			this.middlePoints.add(null);
		}
	}

	/**
	 * This message is displayed in the simulation window, and enables a limited
	 * form of debug output. The content of the message has no material effect on
	 * the system, and can be whatever is desired. In principle, however, it should
	 * display a useful message indicating the current state of the controller.
	 *
	 * @return toString
	 */
	@Override
	public @NonNull String getStatusMessage() {
		return this.mode.toString();
	}

	/**
	 * Process a clock signal which occurs every 5 seconds. This requires reading
	 * the set of incoming messages from the physical units and producing a set of
	 * output messages which are sent back to them.
	 *
	 * @param incoming The set of incoming messages from the physical units.
	 * @param outgoing Messages generated during the execution of this method should
	 *                 be written here.
	 */
	@Override
	public void clock(@NonNull Mailbox incoming, @NonNull Mailbox outgoing) {
		// Extract expected messages
		Message levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
		Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
		Message[] pumpStateMessages = extractAllMatches(MessageKind.PUMP_STATE_n_b, incoming);
		Message[] pumpControlStateMessages = extractAllMatches(MessageKind.PUMP_CONTROL_STATE_n_b, incoming);
		//
		if (transmissionFailure(levelMessage, steamMessage, pumpStateMessages, pumpControlStateMessages)) {
			// Level and steam messages required, so emergency stop.
			this.mode = State.EMERGENCY_STOP;
		}
		//

		// FIXME: this is where the main implementation stems from

		// NOTE: this is an example message send to illustrate the syntax
		outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
	}


	/**
	 * Initialization mode is when you are getting the boiler ready to run, by
	 * filling it up with water.
	 */
	public void initializationMode() {
		int noOfPumpsOn;
		if (this.steamMessage.getDoubleParameter() != 0) { // steam measuring device is defective
			this.mode = State.EMERGENCY_STOP;
			this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
			return;
		}

		// check for water level detection failure
		if (waterLevelFailure()) {
			this.outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
			this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
			this.mode = State.EMERGENCY_STOP;
			return;
		}

		this.waterLevel = this.levelMessage.getDoubleParameter();
		this.steamLevel = this.steamMessage.getDoubleParameter();

		// checks if water level is ready to go to normal
		if (this.levelMessage.getDoubleParameter() > this.configuration.getMinimalNormalLevel()
				&& this.levelMessage.getDoubleParameter() < this.configuration.getMaximalNormalLevel()) {

			turnOnPumps(-1);
			this.outgoing.send(new Message(MessageKind.PROGRAM_READY));
			return;
		}
		if (this.levelMessage.getDoubleParameter() > this.configuration.getMaximalNormalLevel()) {
			// empty
			this.outgoing.send(new Message(MessageKind.VALVE));
			this.openValve = true;
		} else if (this.levelMessage.getDoubleParameter() < this.configuration
				.getMinimalNormalLevel()) { // fill

			if (this.openValve) { // if valve is open, shuts valve
				this.outgoing.send(new Message(MessageKind.VALVE));
				this.openValve = false;
			}
			noOfPumpsOn = estimatePumps(this.steamMessage.getDoubleParameter(),
					this.levelMessage.getDoubleParameter());
			turnOnPumps(noOfPumpsOn);
		}

	}

	/**
	 * Normal mode checks for failures and runs the pumps
	 */
	public void normalMode() {
		this.brokenPumpNo = -1;
		if (howManyBrokenUnits()) {
			this.mode = State.EMERGENCY_STOP;
			this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
			emergencyStopMode();
			return;
		}
		if (steamFailure()) { // if steam failure go to degraded mode
			this.mode = State.DEGRADED;
			this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
			this.outgoing.send(new Message(MessageKind.STEAM_FAILURE_DETECTION));
			this.waterLevel = this.levelMessage.getDoubleParameter();
			degradedMode();
			return;
		}

		// check for water-level detection failure
		if (waterLevelFailure() || this.levelMessage.getDoubleParameter() == 0) {
			// failure, goes to rescue mode
			this.outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
			this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
			this.mode = State.RESCUE;
			this.prevRescueMode = State.NORMAL;
			this.steamLevel = this.steamMessage.getDoubleParameter();
			rescueMode();
			return;
		}
		if (nearMaxMin() || overMax()) { // checks if water is near or over the max
			this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
			this.mode = State.EMERGENCY_STOP;
			emergencyStopMode();
			return;
		}
		int no = pumpFailure();
		if (no != -1) { // check for any pump failure
			this.brokenPumpNo = no;
			this.mode = State.DEGRADED;
			this.prevDegradedMode = State.NORMAL;
			this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
			this.outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, no));
			degradedMode();
			return;
		}
		no = pumpControllerFailure();
		if (no != -1) { // check for any controller failure
			this.mode = State.DEGRADED;
			this.prevDegradedMode = State.NORMAL;
			this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
			this.outgoing.send(new Message(MessageKind.PUMP_CONTROL_FAILURE_DETECTION_n, no));
			degradedMode();
			return;
		}

		// all error messages checked. Can run normal mode as per usual.
		this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
		this.waterLevel = this.levelMessage.getDoubleParameter();
		this.steamLevel = this.steamMessage.getDoubleParameter();
		int noOfPumps = estimatePumps(this.steamMessage.getDoubleParameter(),
				this.levelMessage.getDoubleParameter());
		turnOnPumps(noOfPumps); // pump water in

		if (this.levelMessage.getDoubleParameter() < this.configuration.getMinimalNormalLevel()) {

			noOfPumps = estimatePumps(this.steamMessage.getDoubleParameter(),
					this.levelMessage.getDoubleParameter());
			turnOnPumps(noOfPumps);
		}
		if (this.levelMessage.getDoubleParameter() > this.configuration.getMaximalNormalLevel()) {
			// if it goes above max normal level
			noOfPumps = estimatePumps(this.steamMessage.getDoubleParameter(),
					this.levelMessage.getDoubleParameter());
			turnOnPumps(noOfPumps);
		}

	}

	/**
	 * Degraded mode is entered when a physical unit fails. The controller attempts
	 * to maintain the water level until the broken units are repaired.
	 */
	public void degradedMode() {

		// if failure of water-level measuring unit got to rescueMode()
		if (waterLevelFailure()) {
			this.outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
			this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
			this.mode = State.RESCUE;
			this.prevRescueMode = State.DEGRADED;
			rescueMode();
			return;
		}
		// if water level risks reaching M1 or M2 go to emergencyStopMode()
		if (nearMaxMin()) {
			this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
			this.mode = State.EMERGENCY_STOP;
			emergencyStopMode();
			return;
		}

		for (int i = 0; i < this.incoming.size(); i++) { // check for fixed messages
			Message msg = this.incoming.read(i);
			if (msg.getKind().equals(MessageKind.PUMP_REPAIRED_n)) {
				int pumpNo = msg.getIntegerParameter();
				this.outgoing.send(new Message(MessageKind.PUMP_REPAIRED_ACKNOWLEDGEMENT_n, pumpNo));
				this.mode = this.prevDegradedMode;
			}
			if (msg.getKind().equals(MessageKind.PUMP_CONTROL_REPAIRED_n)) {
				int pumpNo = msg.getIntegerParameter();
				this.outgoing
				.send(new Message(MessageKind.PUMP_CONTROL_REPAIRED_ACKNOWLEDGEMENT_n, pumpNo));
				this.mode = this.prevDegradedMode;

			}
			if (msg.getKind().equals(MessageKind.STEAM_REPAIRED)) {
				this.outgoing.send(new Message(MessageKind.STEAM_REPAIRED_ACKNOWLEDGEMENT));
				this.mode = this.prevDegradedMode;
			}
		}

		if (this.mode.equals(State.NORMAL)) {
			this.brokenPumpNo = -1;
			this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
			return;
		} else if (this.mode.equals(State.READY)) {
			this.brokenPumpNo = -1;
			this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
			return;
		} else { // pump water in
			this.waterLevel = this.levelMessage.getDoubleParameter();
			this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
			this.mode = State.DEGRADED;
			int noOfPumps = estimatePumps(this.steamLevel, this.waterLevel);
			turnOnPumps(noOfPumps);
		}

		// if transmissionFailure go to emergencyStopMode()
	}

	/**
	 * Rescue mode is entered when the water level measuring unit fails. It attempts
	 * to keep the water level between maxNormal and minNormal using estimation.
	 */
	public void rescueMode() {
		// if water level risks reaching M1 or M2 go to emergencyStopMode()
		if (nearMaxRescue() || this.waterLevel <= 0) {
			this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
			this.mode = State.EMERGENCY_STOP;
			emergencyStopMode();
		}

		// checks to see if water level has been repaired.
		if (extractOnlyMatch(MessageKind.LEVEL_REPAIRED, this.incoming) != null) {
			this.outgoing.send(new Message(MessageKind.LEVEL_REPAIRED_ACKNOWLEDGEMENT));
			System.out.println(this.prevRescueMode);
			this.mode = this.prevRescueMode;
			if (this.mode.equals(State.NORMAL)) {
				this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
				this.waterLevel = this.levelMessage.getDoubleParameter();
				return;
			}

			this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
			this.waterLevel = this.levelMessage.getDoubleParameter();
			return;

		}

		this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
		int noOfPumps = estimatePumps(this.steamMessage.getDoubleParameter(), this.waterLevel);
		this.waterLevel = this.rescueWaterEstimate;
		turnOnPumps(noOfPumps);

		// if transmissionFailure go to emergencyStopMode()

	}

	/**
	 * Emergency stop mode stops the program from running.
	 */
	public void emergencyStopMode() {
		this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
		this.mode = State.EMERGENCY_STOP;
	}

	/**
	 * If more than 1 broken physical unit, go to emergency stop mode.
	 *
	 * @return true is more than one physical unit broken, false if not
	 */
	public boolean howManyBrokenUnits() {
		int count = 0;
		if (steamFailure()) {
			count++;
			System.out.println(count);
		}
		if (pumpControllerFailure() != -1) {
			count++;
			System.out.println(count);
		}

		if (this.onOffPumps.size() > 0) {
			for (int i = 0; i < this.pumpStateMessages.length; i++) {
				if (this.pumpStateMessages[i].getBooleanParameter() != this.onOffPumps.get(i)) {
					count++;
				}
				if (i != this.pumpStateMessages.length
						&& (this.onOffPumps.get(i) == false && this.onOffPumps.get(i++) == true)) {
					count++;
				}
			}
		}

		if (count >= 2) {
			System.out.println(count);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Estimate how many pumps to turn on
	 *
	 * @param steam steam message for equation
	 * @param water water message for equation
	 * @return no of pumps to turn on
	 */
	public int estimatePumps(double steam, double water) {
		if (this.levelMessage.getDoubleParameter() > this.configuration.getMaximalNormalLevel()) {
			return -1;
		}
		double midPoint = ((this.configuration.getMaximalNormalLevel()
				- this.configuration.getMinimalNormalLevel()) / 2)
				+ this.configuration.getMinimalNormalLevel();
		double l = water;
		double s = steam;
		double w = this.configuration.getMaximualSteamRate();
		double c = 0;
		double n = 0;
		for (int pumpNo = 0; pumpNo < this.configuration.getNumberOfPumps(); pumpNo++) {
			n = pumpNo + 1;
			c = this.configuration.getPumpCapacity(pumpNo);
			double lmax = l + (5 * c * n) - (5 * s);
			double lmin = l + (5 * c * n) - (5 * w);
			double middlePoint = ((lmax - lmin) / 2) + lmin;
			this.middlePoints.set(pumpNo, middlePoint);
		}
		double closestDistance = 10000;
		int pumpNo = 5;
		for (int i = 0; i < this.middlePoints.size(); i++) {
			double m = this.middlePoints.get(i);
			double distance = Math.abs(midPoint - m);
			if (distance < closestDistance) {
				closestDistance = distance;
				pumpNo = i;
				this.rescueWaterEstimate = this.middlePoints.get(i);
			}
		}
		return pumpNo;
	}

	/**
	 * Turns on number of pumps, turns off the rest of the pipes
	 *
	 * @param numberofPumps no of pumps to turn on
	 */
	public void turnOnPumps(int numberofPumps) {
		if (this.brokenPumpNo > -1) {
			if (stuck) {
				numberofPumps--;
			}
			if (numberofPumps == this.configuration.getNumberOfPumps()) {
				numberofPumps--;
			}
			this.outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, this.brokenPumpNo));
			this.onOffPumps.set(this.brokenPumpNo, false);
		}

		int count = numberofPumps;
		for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
			if (count >= 0 && i != this.brokenPumpNo) { // open
				this.outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
				this.onOffPumps.set(i, true);
				count--;
			} else if (i != this.brokenPumpNo) { // close
				this.outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
				this.onOffPumps.set(i, false);
			}

			if (i == this.configuration.getNumberOfPumps()) {
				return;
			}
		}

	}

	/**
	 * Check to see if the water level measuring unit has failed or not
	 *
	 * @return true if failed, false if not
	 */
	public boolean waterLevelFailure() {
		if (this.levelMessage.getDoubleParameter() < 0) {
			return true;
		} else if (this.levelMessage.getDoubleParameter() >= this.configuration.getCapacity()) {
			return true;
		} else if ((this.mode != State.READY && this.mode != State.WAITING)
				&& (this.levelMessage.getDoubleParameter() > (this.waterLevel * 2))) {
			return true;
		} else if ((this.mode != State.READY && this.mode != State.WAITING)
				&& (this.levelMessage.getDoubleParameter() < (this.waterLevel - (this.waterLevel / 2)))) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Sees if any of the pumps are failing
	 *
	 * @return true if a pump has failed, false if no pumps have failed
	 */
	public int pumpFailure() {
		if (this.onOffPumps.size() > 0) {
			for (int i = 0; i < this.pumpStateMessages.length; i++) {
				if (this.pumpStateMessages[i].getBooleanParameter() != this.onOffPumps.get(i)) {
					this.brokenPumpNo = i;
					this.stuck = this.pumpStateMessages[i].getBooleanParameter();
					return i;
				}
				if (i != this.pumpStateMessages.length
						&& (this.onOffPumps.get(i) == false && this.onOffPumps.get(i++) == true)) {
					this.brokenPumpNo = i;
					this.stuck = this.pumpStateMessages[i].getBooleanParameter();
					return i;
				}
			}
		}
		return -1;
	}

	/**
	 * Checks if any pump controllers are failing
	 *
	 * @return no of controller that is broken
	 */
	public int pumpControllerFailure() {
		if (this.onOffPumps.size() > 0) {
			for (int i = 0; i < this.pumpControlStateMessages.length; i++) {
				if (this.pumpControlStateMessages[i].getBooleanParameter() != this.pumpStateMessages[i]
						.getBooleanParameter()) {
					return i;
				}
			}
		}
		return -1;
	}

	/**
	 * Check to see if water level is near either of the limits.
	 *
	 * @return true if near a limit, false if not
	 */
	public boolean nearMaxMin() {
		double water = this.levelMessage.getDoubleParameter();
		double no = (this.configuration.getMaximalLimitLevel()
				- this.configuration.getMaximalNormalLevel()) / 4;
		if (water > this.configuration.getMaximalLimitLevel()
				|| water > this.configuration.getMaximalLimitLevel() - no) {
			return true;
		} else if (water < this.configuration.getMinimalLimitLevel()
				|| water < this.configuration.getMinimalLimitLevel() + no) {

			return true;
		}
		return false;
	}

	/**
	 * Check to see if water is over maximum water level
	 *
	 * @return true if over, false if not
	 */
	public boolean overMax() {
		if (this.levelMessage.getDoubleParameter() > this.configuration.getMaximalLimitLevel()) {
			return true;
		}
		return false;
	}

	/**
	 * Check to see if the steam level measuring unit is failing or not
	 *
	 * @return true if failing,false if not
	 */
	public boolean steamFailure() {
		double steam = this.steamMessage.getDoubleParameter();
		if (steam < 0) {
			return true;
		}
		if (steam > this.configuration.getMaximualSteamRate()) {
			return true;
		}
		return false;
	}

	/**
	 * Check to see if water level is near either of the limits in rescue mode.
	 *
	 * @return true if near a limit, false if not
	 */
	public boolean nearMaxRescue() {
		double water = this.waterLevel;
		double no = (this.configuration.getMaximalLimitLevel()
				- this.configuration.getMaximalNormalLevel()) / 2;
		if (water > this.configuration.getMaximalLimitLevel()
				|| water > this.configuration.getMaximalLimitLevel() - no) {
			return true;
		} else if (water < this.configuration.getMinimalLimitLevel()
				|| water < this.configuration.getMinimalLimitLevel() + no) {

			return true;
		}
		return false;
	}



	/**
	 * Check whether there was a transmission failure. This is indicated in several
	 * ways. Firstly, when one of the required messages is missing. Secondly, when
	 * the values returned in the messages are nonsensical.
	 *
	 * @param levelMessage      Extracted LEVEL_v message.
	 * @param steamMessage      Extracted STEAM_v message.
	 * @param pumpStates        Extracted PUMP_STATE_n_b messages.
	 * @param pumpControlStates Extracted PUMP_CONTROL_STATE_n_b messages.
	 * @return true, false
	 */
	private boolean transmissionFailure(Message levelMessage, Message steamMessage, Message[] pumpStates,
			Message[] pumpControlStates) {
		// Check level readings
		if (levelMessage == null) {
			// Nonsense or missing level reading
			return true;
		} else if (steamMessage == null) {
			// Nonsense or missing steam reading
			return true;
		} else if (pumpStates.length != configuration.getNumberOfPumps()) {
			// Nonsense pump state readings
			return true;
		} else if (pumpControlStates.length != configuration.getNumberOfPumps()) {
			// Nonsense pump control state readings
			return true;
		}
		// Done
		return false;
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
	private static @Nullable Message extractOnlyMatch(MessageKind kind, Mailbox incoming) {
		Message match = null;
		for (int i = 0; i != incoming.size(); ++i) {
			Message ith = incoming.read(i);
			if (ith.getKind() == kind) {
				if (match == null) {
					match = ith;
				} else {
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
	 * @return The array of matches, which can empty if there were none.
	 */
	private static Message[] extractAllMatches(MessageKind kind, Mailbox incoming) {
		int count = 0;
		// Count the number of matches
		for (int i = 0; i != incoming.size(); ++i) {
			Message ith = incoming.read(i);
			if (ith.getKind() == kind) {
				count = count + 1;
			}
		}
		// Now, construct resulting array
		Message[] matches = new Message[count];
		int index = 0;
		for (int i = 0; i != incoming.size(); ++i) {
			Message ith = incoming.read(i);
			if (ith.getKind() == kind) {
				matches[index++] = ith;
			}
		}
		return matches;
	}
}
