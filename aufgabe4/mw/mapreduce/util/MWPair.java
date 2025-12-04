package mw.mapreduce.util;

/** Helper class for representing Key/Value pairs.
 */
public class MWPair<KEY, VALUE> {
	protected KEY k;
	protected VALUE v;

	/** Create null-valued pair.
	 */
	public MWPair() {}

	/** Create Pair from given objects.
	 *
	 * @param k Key to set.
	 * @param v Value to set.
	 */
	public MWPair(KEY k, VALUE v) {
		this.k = k;
		this.v = v;
	}

	/** Retrieve stored key object.
	 *
	 * @return Previously set key.
	 */
	public KEY getKey() {
		return k;
	}

	/** Retrieve value object associated with key.
	 *
	 * @return Previously set value.
	 */
	public VALUE getValue() {
		return v;
	}
}
