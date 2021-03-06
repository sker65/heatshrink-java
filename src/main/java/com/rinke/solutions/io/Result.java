package com.rinke.solutions.io;

import static com.rinke.solutions.io.Result.Code.ERROR_NULL;

/**
 * common result class for encoder / decoder.
 * @author stefan rinke
 */
public class Result {
	
	public int count;

	public enum Code {
		OK(false), /* data sunk into input buffer */
		FULL(false), /* data sunk into input buffer */
		EMPTY(false), /* input exhausted */
		MORE(false), /* poll again for more output */
		DONE(false), /* encoding is complete */
		ERROR_NULL(true), /* NULL argument */
		ERROR_MISUSE(true),
		ERROR_UNKNOWN(true);

		public boolean error;
		private Code(boolean error) {
			this.error = error;
		}
	}

	public Result.Code code;

	public Result(int count, Result.Code res) {
		super();
		this.count = count;
		this.code = res;
	}
	
	public boolean isError() {
		return code.error;
	}
	
	public static Result res() {
		return new Result(0, ERROR_NULL);
	}

	public static Result res(int count, Code res) {
		return new Result(count, res);
	}

	public static Result res(Code res) {
		return new Result(0, res);
	}



	@Override
	public String toString() {
		return String.format("Result [count=%d, code=%s]", count, code);
	}
}