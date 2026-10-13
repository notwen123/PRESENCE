// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {PresenceRegistry} from "../src/PresenceRegistry.sol";
import {HumanGatedVault, IPresence} from "../src/HumanGatedVault.sol";

contract PresenceTest is Test {
    PresenceRegistry reg;
    address attestor = address(0xA77E57);
    address agent = address(0xA6E);
    bytes32 constant ID = keccak256("sgt:device-1");
    bytes32 MISSION;
    bytes32 constant ROOT = bytes32(uint256(0xabc));

    function setUp() public {
        vm.warp(20_000 days + 3600); // a fixed UTC day
        reg = new PresenceRegistry(attestor);
        MISSION = sha256("quick-free");
    }

    function _record(int64 day, uint8 seq) internal {
        vm.prank(attestor);
        reg.record(ID, MISSION, day, seq, ROOT, 2);
    }

    function test_records_once_and_counts_reputation() public {
        int64 t = reg.today();
        _record(t, 0);
        vm.expectRevert(PresenceRegistry.AlreadyAttested.selector);
        _record(t, 0);
        _record(t, 1); // a second claim index the same day is a separate attestation
        PresenceRegistry.Profile memory p = reg.profileOf(ID);
        assertEq(p.attestations, 2);
        assertEq(p.reputation, 4);
        assertEq(p.streak, 1);
        assertEq(reg.attestationOf(ID, MISSION, reg.today(), 0).evidenceRoot, ROOT);
    }

    function test_only_attestor_and_valid_inputs() public {
        int64 t = reg.today();
        vm.expectRevert(PresenceRegistry.NotAttestor.selector);
        reg.record(ID, MISSION, t, 0, ROOT, 2);
        vm.startPrank(attestor);
        vm.expectRevert(PresenceRegistry.BadAssurance.selector);
        reg.record(ID, MISSION, t, 0, ROOT, 5);
        vm.expectRevert(PresenceRegistry.DayOutOfRange.selector);
        reg.record(ID, MISSION, t - 2, 0, ROOT, 2);
        vm.stopPrank();
    }

    function test_streak_grows_daily_and_resets_after_a_gap() public {
        _record(reg.today(), 0);
        vm.warp(block.timestamp + 1 days);
        _record(reg.today(), 0);
        assertEq(reg.profileOf(ID).streak, 2);
        vm.warp(block.timestamp + 2 days); // missed a day
        _record(reg.today(), 0);
        assertEq(reg.profileOf(ID).streak, 1);
        assertEq(reg.profileOf(ID).bestStreak, 2);
    }

    function test_presence_expires() public {
        assertFalse(reg.isPresent(ID, 2, 1));
        _record(reg.today(), 0);
        assertTrue(reg.isPresent(ID, 2, 1));
        assertFalse(reg.isPresent(ID, 3, 1)); // needs more assurance than was proven
        vm.warp(block.timestamp + 2 days);
        assertFalse(reg.isPresent(ID, 2, 1));
    }

    function test_vault_lets_agent_move_small_amounts_but_large_ones_need_a_present_human() public {
        HumanGatedVault vault = new HumanGatedVault(IPresence(address(reg)), ID, agent, 1 ether);
        vm.deal(address(vault), 10 ether);
        address payable to = payable(address(0xBEEF));
        vm.startPrank(agent);
        vault.withdraw(to, 1 ether); // within the limit: no human needed
        vm.expectRevert(HumanGatedVault.HumanPresenceRequired.selector);
        vault.withdraw(to, 5 ether);
        vm.stopPrank();
        _record(reg.today(), 0); // the owner completes a verified FACEDOWN session
        vm.prank(agent);
        vault.withdraw(to, 5 ether);
        assertEq(to.balance, 6 ether);
        vm.prank(address(0xBAD));
        vm.expectRevert(HumanGatedVault.NotAuthorized.selector);
        vault.withdraw(to, 1);
    }
}
