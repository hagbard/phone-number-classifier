/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

import { Digits } from "./digit-sequence.js";
import { MatchResult } from "./match-results.js";
import { Buffer } from 'buffer';

/**
 * A simple matcher for digit sequences which forms the basis for all phone number classifier and
 * matcher operations. This is powered by a simple finite state machine using data encoded by the
 * DFA compiler in the offline tools. This DOES NOT use regular expressions, since in JavaScript
 * they lack key functionality when dealing with partial matches.
 */
export class DigitSequenceMatcher {
  /** @param bytes The encoded bytes defining the matcher behaviour. */
  constructor(private readonly bytes: Buffer) {
    if (this.bytes.length == 0) {
      throw new Error("matcher data cannot be empty");
    }
  }

  /**
   * Matches a digit sequence according to this matcher's finite state machine.
   *
   * @param digits the iterator of a digit sequence (consumed by this operation).
   * @returns a result with information about the match operation.
  */
  match(digits: Digits): MatchResult {
    let state = this.runMatcher(digits);
    switch (state) {
      case State.Terminal:
        return !digits.hasNext() ? MatchResult.Matched : MatchResult.ExcessDigits;
      case State.Truncated:
        return MatchResult.PartialMatch;
      case State.Invalid:
        return MatchResult.Invalid;
      default:
        throw new Error(`unexpected state: ${state}`);
    }
  }

  private runMatcher(s: Digits): State {
    let data = new DataView(this.bytes);
    let state: State;
    do {
      state = OpCode.decode(data.peekByte(0)).execute(data, s);
    } while (state == State.Continue);
    return state;
  }

  /** @returns a debug friendly string representation of the finite state machine data. */
  toString(): string {
    let data = new DataView(this.bytes);
    let count = data.length();
    let s = count + " :: [ ";
    while (count > 1) {
      s += data.readByte().toString(16) + ", ";
      count--;
    }
    s += data.readByte().toString(16) + " ]";
    return s;
  }
}

enum State {
  Continue,
  Terminal,
  Invalid,
  Truncated,
}

/** Internal abstraction to allow matching over either byte arrays or strings. */
class DataView {
  private position: number = 0;

  constructor(private readonly bytes: Buffer) {}

  length(): number {
    return this.bytes.length;
  }

  /** Return the unsigned byte value at the given offset from the current position. */
  peekByte(offset: number): number {
    return this.bytes.readUInt8(this.position + offset);
  }

  /** Return the unsigned byte value at the current position and move ahead 1 byte. */
  readByte(): number {
    let data: number = this.bytes.readUInt8(this.position);
    this.position += 1;
    return data;
  }

  /** Return the unsigned short value at the current position and move ahead 2 bytes. */
  readShort(): number {
    let data: number = this.bytes.readUInt16BE(this.position);
    this.position += 2;
    return data;
  }

  /** Return the unsigned int value at the current position and move ahead 4 bytes. */
  readInt(): number {
    let data: number = this.bytes.readUInt32BE(this.position);
    this.position += 4;
    return data;
  }

  /** Adjust the current position by the given (non-negative) offset. */
  branch(offset: number): State {
    this.position += offset;
    // Branch 0 is what you get for the terminal zero byte.
    return offset != 0 ? State.Continue : State.Terminal;
  }

  /**
   * Adjust the current position by the unsigned byte offset value read from the current
   * position plus the given index. This is used to implement maps and branching ranges.
   */
  jumpTable(index: number): State {
    return this.branch(this.peekByte(index));
  }
}

/**
 * Implementation of instructions for the phone number matcher state machine.
 *
 * Jump Tables
 *
 * Several instructions use a "jump table" concept which is simply a contiguous region of bytes
 * containing offsets from which a new position is calculated. The new position is the current
 * position (at the start of the jump table) plus the value of the chosen jump offset.
 *
 * [    ...    | JUMP_0 | JUMP_1 | ... | JUMP_N |    ...    |  DEST  |  ...
 *  position --^            ^                               ^
 *             `---index ---'                               |
 *  offset     `----------------  [ position + index ] -----'
 *
 *  position = position + unsignedByteValueAt(position + index)
 *
 * A jump offset of zero signifies that the state jumped to is terminal (this avoids having to jump
 * to a termination byte). A jump table will always occur immediately after an associated
 * instruction and the instruction's stated size includes the number of bytes in the jump table.
 */
abstract class OpCode {
  /**
   * Jumps ahead by between 1 and 4095 bytes from the end of this opcode. This opcode does not
   * consume any input.
   *
   * This is a variable length instruction, taking one byte for offsets up to 15 and (if EXT is set)
   * two bytes for larger offsets up to 4095. The jump offset signifies how many bytes to skip after
   * this instruction.
   *
   * As a special case, a single byte branch with a jump offset of zero (represented by a single
   * zero byte) can be used to signify that the current state is terminal and the state machine
   * should exit (a zero jump offset never makes sense in any instruction).
   *
   * [ 0 | 0 |  JUMP   ]
   * [ 0 | 1 |  JUMP   |  EXT_JUMP   ]
   *  <3>.<1>.<-- 4 -->.<---- 8 ---->
   */
  static readonly Branch: OpCode = new class extends OpCode {
    execute(data: DataView, digits: Digits): State {
      let op = data.readByte();
      let offset = op & 0xF;
      // Read and apply EXT_JUMP if extension bit set.
      if ((op & (1 << 4)) != 0) {
        offset = (offset << 8) + data.readByte();
      }
      return data.branch(offset);
    }
  }(0);

  /**
   * Accepts a single input (and transition to a single state). Inputs not matching "VAL" are
   * invalid from the current state. If "TRM" is set then the state being transitioned from may
   * terminate.
   *
   * [ 1 |TRM|  VAL  ]
   *  <3>.<1>.<- 4 ->
   */
  static readonly Single: OpCode = new class extends OpCode {
    execute(data: DataView, digits: Digits): State {
      let op = data.readByte();
      if (!digits.hasNext()) {
        return ((op & (1 << 4)) != 0) ? State.Terminal : State.Truncated;
      }
      let n = digits.next();
      return ((op & 0xF) == n) ? State.Continue : State.Invalid;
    }
  }(1);

  /**
   * Accept any input to transition to a single state one or more times.
   *
   * If "TRM" is set then every state that is transitioned from may terminate.
   *
   * [ 2 |TRM| NUM-1 ]
   *  <3>.<1>.<- 4 ->
   */
  static readonly Any: OpCode = new class extends OpCode {
    execute(data: DataView, digits: Digits): State {
      let op = data.readByte();
      let num = (op & 0xF) + 1;
      let isTerminating = (op & (1 << 4)) != 0;
      while (num-- > 0) {
        if (!digits.hasNext()) {
          return isTerminating ? State.Terminal : State.Truncated;
        }
        digits.next();
      }
      return State.Continue;
    }
  }(2);

  /**
   * Accepts multiple inputs to transition to one or two states. The bit-set has the Nth bit set if
   * we should accept digit N (bit-0 is the lowest bit of the 2 byte form of the instruction).
   *
   * This is a variable length instruction which either treats non-matched inputs as invalid
   * (2 byte form) or branches to one of two states via a 2-entry jump table (4 byte form).
   *
   * If "TRM" is set then the state being transitioned from may terminate.
   *
   * [ 3 |TRM| 0 |---|   BIT SET  ]
   * [ 3 |TRM| 1 |---|   BIT SET  |  JUMP_IN  | JUMP_OUT  ]
   *  <3>.<1>.<1>.<1>.<--- 10 --->.<--- 8 --->.<--- 8 --->
   */
  static readonly Range: OpCode = new class extends OpCode {
    execute(data: DataView, digits: Digits): State {
      let op = data.readShort();
      if (!digits.hasNext()) {
        return ((op & (1 << 12)) != 0) ? State.Terminal : State.Truncated;
      }
      let n = digits.next();
      if ((op & (1 << 11)) == 0) {
        // 2 byte form, non-matched input is invalid.
        return ((op & (1 << n)) != 0) ? State.Continue : State.Invalid;
      }
      // 4 byte form uses jump table (use bitwise negation so a set bit becomes a 0 index).
      return data.jumpTable((~op >>> n) & 1);
    }
  }(3);

  /**
   * Accept multiple inputs to transition to between one and ten states via jump offsets. Inputs
   * not encoded in "CODED MAP" are invalid from the current state.
   *
   * Because there is no room for a termination bit in this instruction, there is an alternate
   * version, {@code TMAP}, which should be used when transitioning from a terminating state.
   *
   * TODO: Figure out if we can save one bit here and merge MAP and TMAP.
   *
   * [ 4 |      CODED MAP       |  JUMP_1   |  ... |  JUMP_N   ]
   *  <3>.<-------- 29 -------->.<--- 8 --->.  ... .<--- 8 --->
   */
  static readonly Map: OpCode = new class extends OpCode {
    execute(data: DataView, digits: Digits): State {
      return OpCode.map(data, digits, State.Truncated);
    }
  }(4);

  /**
   * Like MAP but transitions from a terminating state.
   */
  static readonly Tmap: OpCode = new class extends OpCode {
    execute(data: DataView, digits: Digits): State {
      return OpCode.map(data, digits, State.Terminal);
    }
  }(5);

  /**
   * Encode maps as 29 bits where each digit takes a different number of bits to encode its offset.
   * Specifically:
   *
   * >> The first entry (matching 0) has only two possible values (it is either not present or maps
   * to the first entry in the jump table), so takes only 1 bit.
   * >> The second entry (matching 1) has three possible values (not present or maps to either the
   * first or second entry in the jump table), so it takes 2 bits.
   * >> In general the entry matching digit N has (N+1) possible states and takes log2(N+1) bits.
   */
  private static decodeMapIndex(op: number, n: number): number {
    switch (n) {
      case 0: return (op >>> 0) & 0x1;
      case 1: return (op >>> 1) & 0x3;
      case 2: return (op >>> 3) & 0x3;
      case 3: return (op >>> 5) & 0x7;
      case 4: return (op >>> 8) & 0x7;
      case 5: return (op >>> 11) & 0x7;
      case 6: return (op >>> 14) & 0x7;
      case 7: return (op >>> 17) & 0xF;
      case 8: return (op >>> 21) & 0xF;
      case 9: return (op >>> 25) & 0xF;
    }
    throw new Error(`invalid digit: ${n}`);
  }

  /**
   * Executes a map instruction by decoding the map data and selecting a jump offset to apply.
   */
  private static map(data: DataView, digits: Digits , noInputState: State): State {
    let op = data.readInt();
    if (!digits.hasNext()) {
      return noInputState;
    }
    let n = digits.next();
    // Coded indices are 1-to-10 (0 is the "invalid" state).
    let index = OpCode.decodeMapIndex(op, n);
    if (index == 0) {
      return State.Invalid;
    }
    // Jump offsets are zero based.
    return data.jumpTable(index - 1);
  }

  private static readonly OPCODES: OpCode[] = [
    OpCode.Branch,
    OpCode.Single,
    OpCode.Any,
    OpCode.Range,
    OpCode.Map,
    OpCode.Tmap,
  ];

  static decode(unsignedByte: number): OpCode {
    let index = unsignedByte >>> 5;
    if (index >= OpCode.OPCODES.length) {
      throw new Error(`bad opcode id from byte: ${unsignedByte.toString(16)}`);
    }
    return OpCode.OPCODES[index];
  }

  constructor(private readonly opCode: number) {}

  abstract execute(data: DataView, digits: Digits): State;
}