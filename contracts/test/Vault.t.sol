// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {PresenceVault} from "../src/PresenceVault.sol";

contract VaultTest is Test {
    PresenceVault v;
    address attestor = address(0xA77E57);
    address user = address(0x05E5);
    address sponsor = address(0x5905);

    function setUp() public {
        v = new PresenceVault(attestor);
        vm.deal(user, 10 ether); vm.deal(sponsor, 10 ether);
    }

    function test_stake_fund_pay_burn() public {
        vm.prank(user); v.stake{value: 1 ether}(bytes32("s1"));
        vm.prank(sponsor); v.fund{value: 2 ether}();
        assertEq(address(v).balance, 3 ether);
        vm.prank(attestor); v.pay(payable(user), 1.2 ether, bytes32("s1")); // stake back + bonus
        assertEq(user.balance, 10.2 ether);
        vm.prank(attestor); v.burn(0.5 ether);
        assertEq(v.BURN().balance, 0.5 ether);
    }

    function test_only_attestor_pays_and_zero_stake_rejected() public {
        vm.prank(user); v.stake{value: 1 ether}(bytes32("s1"));
        vm.prank(user); vm.expectRevert(PresenceVault.NotAttestor.selector); v.pay(payable(user), 1 ether, bytes32("s1"));
        vm.expectRevert(PresenceVault.NotAttestor.selector); v.burn(1);
        vm.prank(user); vm.expectRevert(PresenceVault.NothingSent.selector); v.stake(bytes32("s2"));
    }
}
