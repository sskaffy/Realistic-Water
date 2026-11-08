package me.skaffy.realistic;

import java.util.ArrayList;
import java.util.List;

public final class BoatHull {
	public static final double HALF_WIDTH = 0.625;
	public static final double HALF_LENGTH = 0.875;
	public static final double RIM = 0.5625;
	private static final double FLOOR = 0.1875;
	public static final double WALL = 0.125;
	private static final double RAFT_DECK = 0.375;
	public static final double RAFT_DENSITY = 0.4;
	public static final double PLANK_DENSITY = 600.0;
	public static final int[][] CELLS = {{-1, 0, -1}, {0, 0, -1}, {-1, 0, 0}, {0, 0, 0}};

	private BoatHull() {
	}

	public static double[] seat(boolean raft, boolean chest, int index, int riders) {
		double z = chest ? 0.15 : 0.0;
		if (riders > 1) {
			z = index == 0 ? 0.2 : -0.6;
		}
		return new double[]{0.0, floorTop(raft), z};
	}

	public static double floorTop(boolean raft) {
		return raft ? RAFT_DECK : FLOOR;
	}

	private static double[][] solidGrid(boolean raft) {
		double w = HALF_WIDTH;
		double l = HALF_LENGTH;
		if (raft) {
			return new double[][]{{-w, 0, -l, w, RAFT_DECK, l}};
		}
		return new double[][]{
			{-w, 0, -l, w, FLOOR, l},
			{-w, FLOOR, -l, -w + WALL, RIM, l},
			{w - WALL, FLOOR, -l, w, RIM, l},
			{-w + WALL, FLOOR, l - WALL, w - WALL, RIM, l},
			{-w + WALL, FLOOR, -l, w - WALL, RIM, -l + WALL}};
	}

	private static double[][] envelopeGrid(boolean raft) {
		return new double[][]{{-HALF_WIDTH, 0, -HALF_LENGTH, HALF_WIDTH, raft ? RAFT_DECK : RIM, HALF_LENGTH}};
	}

	public static double[][] solid(boolean raft, int cx, int cy, int cz) {
		return clip(solidGrid(raft), cx, cy, cz);
	}

	public static double[][] envelope(boolean raft, int cx, int cy, int cz) {
		return clip(envelopeGrid(raft), cx, cy, cz);
	}

	private static double[][] clip(double[][] boxes, int cx, int cy, int cz) {
		List<double[]> out = new ArrayList<>();
		for (double[] b : boxes) {
			double[] c = {
				Math.max(b[0] - cx, 0.0), Math.max(b[1] - cy, 0.0), Math.max(b[2] - cz, 0.0),
				Math.min(b[3] - cx, 1.0), Math.min(b[4] - cy, 1.0), Math.min(b[5] - cz, 1.0)};
			if (c[3] - c[0] > 1.0E-6 && c[4] - c[1] > 1.0E-6 && c[5] - c[2] > 1.0E-6) {
				out.add(c);
			}
		}
		return out.toArray(new double[0][]);
	}
}
