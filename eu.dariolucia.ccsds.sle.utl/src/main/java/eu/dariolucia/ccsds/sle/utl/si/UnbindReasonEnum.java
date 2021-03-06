/*
 *  Copyright 2018-2019 Dario Lucia (https://www.dariolucia.eu)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package eu.dariolucia.ccsds.sle.utl.si;

public enum UnbindReasonEnum {
	END((byte) 0), 
	SUSPEND((byte) 1), 
	VERSION_NOT_SUPPORTED((byte) 2), 
	OTHER((byte) 127);

	private final byte code;

	UnbindReasonEnum(byte code) {
		this.code = code;
	}

	public byte getCode() {
		return this.code;
	}

	public static UnbindReasonEnum fromCode(byte code) {
		for(UnbindReasonEnum en : UnbindReasonEnum.values()) {
			if(en.getCode() == code) {
				return  en;
			}
		}
		throw new IllegalArgumentException("Unbind Reason code not recognized: " + code);
	}
}
