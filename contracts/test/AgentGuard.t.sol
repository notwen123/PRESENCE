// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {AgentGuard} from "../src/AgentGuard.sol";
import {PresenceRegistry} from "../src/PresenceRegistry.sol";
import {SustainedHumanAirdrop, IPresenceDays} from "../src/SustainedHumanAirdrop.sol";

contract AgentGuardTest is Test {
    AgentGuard g;
    address agent = address(0xA6E); address attestor = address(0xA77); address payable to = payable(address(0xBEEF));

    uint256 constant PHONE = 0xC0FFEE; // the phone's P-256 private key (test only)
    bytes32 x; bytes32 y; bytes32 acct;

    function setUp() public {
        g = new AgentGuard(agent, attestor, 0.01 ether);
        vm.deal(address(g), 1 ether);
        (uint256 px, uint256 py) = vm.publicKeyP256(PHONE); x = bytes32(px); y = bytes32(py); acct = g.accountOf(x, y);
    }

    function phoneSigns(bytes32 id, uint256 key) internal pure returns (bytes32 r, bytes32 s) {
        return vm.signP256(key, sha256(abi.encodePacked(id)));
    }

    function test_small_goes_through_large_waits_for_a_signed_knock() public {
        g.bindDevice(x, y);
        vm.prank(agent); g.send(acct, to, 0.005 ether);
        assertEq(to.balance, 0.005 ether);
        vm.prank(agent); bytes32 id = g.send(acct, to, 0.03 ether);
        assertEq(to.balance, 0.005 ether); // blocked: waiting for the human
        (bytes32 fr, bytes32 fs) = phoneSigns(id, 0xBAD); // someone else's key
        vm.prank(attestor); vm.expectRevert(AgentGuard.BadSignature.selector); g.approve(id, fr, fs);
        (bytes32 r, bytes32 s) = phoneSigns(id, PHONE);
        vm.prank(agent); vm.expectRevert(AgentGuard.NotAttestor.selector); g.approve(id, r, s);
        vm.prank(agent); bytes32 other = g.send(acct, to, 0.5 ether);
        vm.prank(attestor); vm.expectRevert(AgentGuard.BadSignature.selector); g.approve(other, r, s); // can't reuse for another intent
        vm.prank(attestor); g.approve(id, r, s);
        assertEq(to.balance, 0.035 ether);
        vm.prank(attestor); vm.expectRevert(AgentGuard.UnknownIntent.selector); g.approve(id, r, s); // no replay
    }

    function test_intent_expires_and_device_binds_once() public {
        vm.prank(agent); vm.expectRevert(AgentGuard.NoDevice.selector); g.send(acct, to, 0.03 ether);
        g.bindDevice(x, y);
        vm.expectRevert(AgentGuard.AlreadyBound.selector); g.bindDevice(x, y);
        vm.prank(agent); bytes32 id = g.send(acct, to, 0.03 ether);
        vm.warp(block.timestamp + 121);
        (bytes32 r, bytes32 s) = phoneSigns(id, PHONE);
        vm.prank(attestor); vm.expectRevert(AgentGuard.Expired.selector); g.approve(id, r, s);
    }

    function test_sustained_airdrop_needs_presence_on_enough_days() public {
        vm.warp(20_000 days + 60);
        PresenceRegistry reg = new PresenceRegistry(attestor);
        SustainedHumanAirdrop drop = new SustainedHumanAirdrop(IPresenceDays(address(reg)), 0.1 ether, 3, 30, attestor);
        vm.deal(address(drop), 1 ether);
        bytes32 id = keccak256("human"); bytes32 m = sha256("quick-free");
        for (uint256 i = 0; i < 3; i++) {
            if (i > 0) vm.warp(block.timestamp + 1 days);
            int64 t = reg.today();
            vm.prank(attestor); reg.record(id, m, t, 0, bytes32(0), 2);
            if (i < 2) { vm.prank(attestor); vm.expectRevert(); drop.claim(id, to); }
        }
        assertEq(reg.presentDays(id, reg.today() - 29, reg.today()), 3);
        vm.prank(attestor); drop.claim(id, to);
        assertEq(to.balance, 0.1 ether);
        vm.prank(attestor); vm.expectRevert(SustainedHumanAirdrop.AlreadyClaimed.selector); drop.claim(id, to);
    }
}
