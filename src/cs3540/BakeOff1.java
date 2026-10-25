package cs3540;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import processing.core.PApplet;
import processing.core.PImage;

public class BakeOff1 extends PApplet {
	// when in doubt, consult the Processsing reference:
	// https://processing.org/reference/
	// The argument passed to main must match the class name
	public static void main(String[] args) {
		// Tell processing what class we want to run.
		PApplet.main("cs3540.BakeOff1");
	}

	int margin = 200; // set the margin around the squares
	final int padding = 50; // padding between buttons and also their width/height
	final int buttonSize = 40; // padding between buttons and also their width/height
	ArrayList<Integer> trials = new ArrayList<Integer>(); // contains the order of buttons that activate in the test
	int trialNum = 0; // the current trial number (indexes into trials array above)
	int startTime = 0; // time starts when the first click is captured
	int finishTime = 0; // records the time of the final click
	int hits = 0; // number of successful clicks
	int misses = 0; // number of missed clicks
	Robot robot; // initialized in setup

	int numRepeats = 10; // FITTS: 10 repeats -> 16 x 10 = 160 clicks

	// ===================== FITTS DATA COLLECTION =====================

	// >>> Nick = 1, Danny = 2, Henderson = 3 <<<
	final int PARTICIPANT_ID = 1;

	// Target sizes in pixels. The size changes on every click and cycles through this list
	// in a fixed order (trial 0 -> 20, trial 1 -> 30, ...). The order is the same for every
	// team member. Each button stays centered in its grid cell, so the button positions
	// don't move. The cell pitch is padding + buttonSize = 90px, so keep every size under ~80.
	// To use one fixed size, set this to { 40 }.
	final int[] SIZES = { 20, 30, 40, 55, 70 };

	// The spec says to throw away the first click. Instead of losing a trial, the run starts
	// with one extra "start" click that isn't logged. It gives trial 1 a real starting point,
	// so all 16 x numRepeats = 160 trials are recorded.
	boolean started = false;

	PrintWriter logFile; // the CSV file written next to your project's src folder
	int lastClickX, lastClickY; // cursor position at the START of the current trial
	int lastClickTime; // millis() at the start of the current trial

	/** Target width for the given trial (systematic, the same for everyone). */
	int sizeForTrial(int t) {
		return SIZES[t % SIZES.length];
	}

	/** Size used for drawing and hit-testing right now. */
	int currentSize() {
		return sizeForTrial(min(trialNum, trials.size() - 1));
	}

	// ===================== popup notifications =====================
	PImage missImg; // shown on a miss
	PImage hitImg; // shown on a hit
	ArrayList<Popup> popups = new ArrayList<Popup>(); // popups currently on screen

	final float POPUP_RISE_SPEED = 120; // pixels per second
	final int POPUP_LIFETIME = 1200; // milliseconds

	/**
	 * An image that appears at a point, floats upward, and fades out.
	 * It's an inner class, so it can call Processing methods (image, tint, millis...) directly.
	 */
	class Popup {
		PImage img;
		float x, y, startY;
		int bornAt;
		float alpha = 255;

		Popup(PImage img, float x, float y) {
			this.img = img;
			this.x = x;
			this.y = y;
			this.startY = y;
			this.bornAt = millis();
		}

		void update() {
			float ageMs = millis() - bornAt;
			float t = constrain(ageMs / POPUP_LIFETIME, 0, 1); // 0 -> 1 over its life

			y = startY - POPUP_RISE_SPEED * (ageMs / 1000f); // time-based, so frame rate doesn't matter

			// fully visible for the first 40% of its life, then fade out
			alpha = t < 0.4f ? 255 : map(t, 0.4f, 1, 255, 0);
		}

		void display() {
			pushStyle();
			imageMode(CENTER);
			tint(255, alpha); // 2nd argument = transparency
			image(img, x, y);
			popStyle();
		}

		boolean isDead() {
			return millis() - bornAt >= POPUP_LIFETIME || y + img.height / 2f < 0;
		}
	}

	void spawnPopup(PImage img, float x0, float y0) {
		if (img == null)
			return;

		// The grid's edges are based on the fixed cell layout, not the changing button size,
		// so the popup spot doesn't jump around when the size changes.
		int pitch = padding + buttonSize;
		float gridRight = margin + 3 * pitch + buttonSize;
		float gridMidY = margin + (3 * pitch + buttonSize) / 2f;

		// center of the empty space between the grid and the right edge of the window
		float x = (gridRight + width) / 2f;
		float y = gridMidY;

		popups.add(new Popup(img, x, y));
	}

	void updateAndDrawPopups() {
		for (int i = popups.size() - 1; i >= 0; i--) { // backwards so removing is safe
			Popup p = popups.get(i);
			p.update();
			p.display();
			if (p.isDead())
				popups.remove(i);
		}
	}

	/**
	 * https://processing.org/reference/settings_.html#:~:text=The%20settings()%20method%20runs,commands%20in%20the%20Processing%20API.
	 */
	public void settings() {
		size(700, 700);
	}

	// Shrinks an image (in place) to fit inside maxW x maxH, keeping proportions.
	PImage capSize(PImage img, float maxW, float maxH) {
		if (img == null)
			return null;
		float scale = min(maxW / img.width, maxH / img.height, 1); // never scale up
		if (scale < 1)
			img.resize(round(img.width * scale), round(img.height * scale));
		return img;
	}

	/**
	 * // https://processing.org/reference/setup_.html
	 */
	public void setup() {
		// noCursor(); // hides the system cursor if you want
		noStroke(); // turn off all strokes, we're just using fills here (can change this if you
					// want)
		textFont(createFont("Arial", 16)); // sets the font to Arial size 16
		textAlign(CENTER);
		frameRate(60); // normally you can't go much higher than 60 FPS.
		ellipseMode(CENTER); // ellipses are drawn from the center (BUT RECTANGLES ARE NOT!)
		// rectMode(CENTER); //enabling will break the scaffold code, but you might find
		// it easier to work with centered rects

		// ===== load popup images =====
		missImg = capSize(loadImage("src/data/miss.png"), 150, 80);
		hitImg = capSize(loadImage("src/data/hit_streak_1.png"), 150, 80); // the plain "hit" image

		try {
			robot = new Robot(); // create a "Java Robot" class that can move the system cursor
		} catch (AWTException e) {
			e.printStackTrace();
		}

		// ===DON'T MODIFY MY RANDOM ORDERING CODE==
		for (int i = 0; i < 16; i++) // generate list of targets and randomize the order
			// number of buttons in 4x4 grid
			for (int k = 0; k < numRepeats; k++)
				// number of times each button repeats
				trials.add(i);

		Collections.shuffle(trials); // randomize the order of the buttons
		System.out.println("trial order: " + trials); // print out order for reference

		// ===== FITTS: open a CSV file for this run =====
		// The file name includes the participant and the date and time, so re-runs never overwrite.
		String fileName = "fitts_results" + PARTICIPANT_ID + "_" + year() + nf(month(), 2) + nf(day(), 2) + "_"
				+ nf(hour(), 2) + nf(minute(), 2) + nf(second(), 2) + ".csv";
		logFile = createWriter(fileName);
		String header = "trial,participant,startX,startY,targetX,targetY,width,time,hit";
		logFile.println(header);
		System.out.println("Logging to: " + sketchPath(fileName));
		System.out.println(header);

		surface.setLocation(0, 0);// put window in top left corner of screen (doesn't always work)
	}

	public void draw() {
		background(0); // set background to black

		if (trialNum >= trials.size()) // check to see if test is over
		{
			float timeTaken = (finishTime - startTime) / 1000f;
			float accuracy = (float) hits * 100f / (float) (hits + misses);
			float penalty = constrain(((95f - accuracy) * .2f), 0, 100);
			fill(255); // set fill color to white
			// write to screen (not console)
			text("Finished!", width / 2, height / 2);
			text("Hits: " + hits, width / 2, height / 2 + 20);
			text("Misses: " + misses, width / 2, height / 2 + 40);
			text("Accuracy: " + accuracy + "%", width / 2, height / 2 + 60);
			text("Total time taken: " + timeTaken + " sec", width / 2, height / 2 + 80);
			text("Average time for each button: " + nf((timeTaken) / (float) (hits + misses), 0, 3) + " sec", width / 2,
					height / 2 + 100);
			text("Average time for each button + penalty: "
					+ nf(((timeTaken) / (float) (hits + misses) + penalty), 0, 3) + " sec", width / 2,
					height / 2 + 140);

			// FITTS: the 99% accuracy rule
			if (accuracy < 99f) {
				fill(255, 80, 80);
				text("Below 99% accuracy - discard this file and run the experiment again.", width / 2,
						height / 2 + 180);
			} else {
				fill(80, 255, 120);
				text("Accuracy OK (>= 99%) - this data set is usable.", width / 2, height / 2 + 180);
			}

			updateAndDrawPopups(); // let the final click's popup finish floating away
			return; // return, nothing else to do now test is over
		}

		fill(255); // set fill color to white
		if (!started) // FITTS: start screen
			text("Click anywhere to start", width / 2, margin / 2);
		else
			text((trialNum + 1) + " of " + trials.size(), 40, 20); // display what trial the user is on

		// ARROWS: drawn BEFORE the buttons so the buttons always sit on top
		// (keeps every non-target button fully visible -> golden rule safe)
		int cur = trials.get(trialNum);
		if (trialNum > 0) // arrow 1: button you just clicked -> current target
			drawArrow(trials.get(trialNum - 1), cur, color(0, 255, 255), 4);
		if (trialNum + 1 < trials.size()) // arrow 2: current target -> next (look-ahead 1)
			drawArrow(cur, trials.get(trialNum + 1), color(255, 200, 0, 150), 2);

		for (int i = 0; i < 16; i++)// for all button
			drawButton(i); // draw button

		updateAndDrawPopups(); // draw popups

		fill(255, 0, 0, 200); // set fill color to translucent red
		ellipse(mouseX, mouseY, 20, 20); // draw user cursor as a circle with a diameter of 20

	}

	public void mousePressed() // test to see if hit was in target!
	{
		if (trialNum >= trials.size()) // check if task is done
			return;

		// FITTS: the start click (thrown away, not logged). It sets where and when trial 1 begins.
		if (!started) {
			started = true;
			startTime = millis();
			lastClickX = mouseX;
			lastClickY = mouseY;
			lastClickTime = millis();
			return;
		}

		Rectangle bounds = getButtonLocation(trials.get(trialNum));

		// check to see if cursor was inside button
		boolean hit = (mouseX > bounds.x && mouseX < bounds.x + bounds.width)
				&& (mouseY > bounds.y && mouseY < bounds.y + bounds.height);

		if (hit) // test to see if hit was within bounds
		{
			hits++;
			spawnPopup(hitImg, mouseX, mouseY);
		} else {
			misses++;
			spawnPopup(missImg, mouseX, mouseY);
		}

		// ===== FITTS: log one row for this click =====
		// Every trial is logged. The trial starts at the previous click (or at the start click).
		{
			float targetX = bounds.x + bounds.width / 2f;
			float targetY = bounds.y + bounds.height / 2f;
			float timeTaken = (millis() - lastClickTime) / 1000f;

			String row = (trialNum + 1) + "," + PARTICIPANT_ID + "," + lastClickX + "," + lastClickY + ","
					+ nf(targetX, 0, 1) + "," + nf(targetY, 0, 1) + "," + bounds.width + ","
					+ nf(timeTaken, 0, 3) + "," + (hit ? 1 : 0);
			logFile.println(row);
			logFile.flush(); // write immediately, so a crash or early quit doesn't lose data
			System.out.println(row);
		}
		
		if (trialNum == trials.size() - 1) // check if final click
		{
			finishTime = millis();
			// write to terminal some output:
			System.out.println("we're all done!");
		}

		// this click is where the next trial starts
		lastClickX = mouseX;
		lastClickY = mouseY;
		lastClickTime = millis();

		trialNum++; // Increment trial number

		if (trialNum >= trials.size()) { // test over: close the file
			logFile.flush();
			logFile.close();
			System.out.println("Accuracy: " + nf(hits * 100f / (hits + misses), 0, 2) + "%");
		}

		// in this example design, I move the cursor back to the middle after each click
		// Note. When running from eclipse the robot class affects the whole screen not
		// just the GUI, so the mouse may move outside of the GUI.
		// robot.mouseMove(width/2, (height)/2); //on click, move cursor to roughly
		// center of window!
	}

	// for a given button ID, what is its location and size.
	// FITTS: each button stays centered in its fixed grid cell, and only its size changes.
	public Rectangle getButtonLocation(int i) {
		int s = currentSize();
		int pitch = padding + buttonSize; // fixed 90px cell spacing
		float centerX = (i % 4) * pitch + margin + buttonSize / 2f;
		float centerY = (i / 4) * pitch + margin + buttonSize / 2f;
		int x = round(centerX - s / 2f);
		int y = round(centerY - s / 2f);

		return new Rectangle(x, y, s, s);
	}

	// you can edit this method to change how buttons appear
	public void drawButton(int i) {
		Rectangle bounds = getButtonLocation(i);

		if (trials.get(trialNum) == i) // see if current button is the target
			fill(0, 255, 255); // if so, fill cyan
		else
			fill(200); // if not, fill gray

		rect(bounds.x, bounds.y, bounds.width, bounds.height);
	}

	// ===================== ARROW HELPERS =====================

	// center of button i (works for any button size)
	float cx(int i) { Rectangle r = getButtonLocation(i); return r.x + r.width / 2f; }
	float cy(int i) { Rectangle r = getButtonLocation(i); return r.y + r.height / 2f; }

	// k-th "lane": the middle of the gap before column/row k (k = 0..4).
	// lane(0) is just above/left of the grid, lane(4) just below/right.
	float lane(int k) { return margin - padding / 2f + k * (padding + buttonSize); }

	// does the segment (x1,y1)-(x2,y2) pass through any button except a and b?
	boolean segmentHitsButtons(float x1, float y1, float x2, float y2, int a, int b) {
		float grow = 3; // small safety margin so thick lines don't graze a box
		for (int i = 0; i < 16; i++) {
			if (i == a || i == b) continue;
			Rectangle r = getButtonLocation(i);
			for (int s = 0; s <= 100; s++) { // sample points along the line
				float t = s / 100f;
				float x = lerp(x1, x2, t), y = lerp(y1, y2, t);
				if (x > r.x - grow && x < r.x + r.width + grow && y > r.y - grow && y < r.y + r.height + grow)
					return true;
			}
		}
		return false;
	}

	// list of points from center of a to center of b that never crosses another button.
	// Straight line if it's clear; otherwise travel through the gaps between buttons.
	ArrayList<float[]> routeBetween(int a, int b) {
		ArrayList<float[]> pts = new ArrayList<float[]>();
		float sx = cx(a), sy = cy(a), ex = cx(b), ey = cy(b);
		pts.add(new float[] { sx, sy });
		if (segmentHitsButtons(sx, sy, ex, ey, a, b)) {
			int c1 = a % 4, r1 = a / 4, c2 = b % 4, r2 = b / 4;
			// gap corner next to a (on the side facing b), and next to b (facing a)
			int kx1 = c1 + (c2 > c1 ? 1 : 0), ky1 = r1 + (r2 > r1 ? 1 : 0);
			int kx2 = c2 + (c1 > c2 ? 1 : 0), ky2 = r2 + (r1 > r2 ? 1 : 0);
			pts.add(new float[] { lane(kx1), lane(ky1) }); // step out into the gap
			pts.add(new float[] { lane(kx2), lane(ky1) }); // travel along a horizontal gap
			pts.add(new float[] { lane(kx2), lane(ky2) }); // travel along a vertical gap
		}
		pts.add(new float[] { ex, ey });
		return pts;
	}

	// draw an arrow from button a to button b, with the head stopping at b's edge
	void drawArrow(int a, int b, int col, float weight) {
		ArrayList<float[]> pts = routeBetween(a, b);
		float[] last = pts.get(pts.size() - 1), prev = pts.get(pts.size() - 2);

		// direction of the final segment
		float dx = last[0] - prev[0], dy = last[1] - prev[1];
		float len = sqrt(dx * dx + dy * dy);
		dx /= len; dy /= len;

		// pull the tip back from b's center to just outside its edge
		float half = currentSize() / 2f; // FITTS: use the current (changing) size
		float toEdge = min(abs(dx) > 0.001f ? half / abs(dx) : 9999, abs(dy) > 0.001f ? half / abs(dy) : 9999);
		float tipX = last[0] - dx * (toEdge + 3), tipY = last[1] - dy * (toEdge + 3);

		// body
		stroke(col);
		strokeWeight(weight);
		noFill();
		beginShape();
		for (int i = 0; i < pts.size() - 1; i++)
			vertex(pts.get(i)[0], pts.get(i)[1]);
		vertex(tipX, tipY);
		endShape();

		// head (triangle)
		float headLen = 6 + weight * 2, headW = 4 + weight;
		noStroke();
		fill(col);
		triangle(tipX, tipY,
				tipX - dx * headLen - dy * headW, tipY - dy * headLen + dx * headW,
				tipX - dx * headLen + dy * headW, tipY - dy * headLen - dx * headW);
	}

	public void mouseMoved() {
		// can do stuff everytime the mouse is moved (i.e., not clicked)
		// https://processing.org/reference/mouseMoved_.html
	}

	public void mouseDragged() {
		// can do stuff everytime the mouse is dragged
		// https://processing.org/reference/mouseDragged_.html
	}

	public void keyPressed() {
		// can use the keyboard if you wish
		// https://processing.org/reference/keyTyped_.html
		// https://processing.org/reference/keyCode.html
	}
}