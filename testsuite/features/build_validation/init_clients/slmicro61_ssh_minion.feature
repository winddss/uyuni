# Copyright (c) 2025 SUSE LLC
# Licensed under the terms of the MIT license.

# Beware: After altering the system e.g. package installation/removal, the system
#         has to be rebooted to take effect due to the nature of SL Micro.

@slmicro61_ssh_minion
Feature: Bootstrap a SL Micro 6.1 Salt SSH minion

  Scenario: Clean up sumaform leftovers on a SL Micro SSH 6.1 SSH minion
    When I perform a full salt minion cleanup on "slmicro61_ssh_minion"

  Scenario: Log in as admin user
    Given I am authorized for the "Admin" section

  Scenario: Bootstrap a SL Micro 6.1 SSH minion
    When I follow the left menu "Systems > Bootstrapping"
    Then I should see a "Bootstrap Minions" text
    When I check "manageWithSSH"
    And I enter the hostname of "slmicro61_ssh_minion" as "hostname"
    And I enter "linux" as "password"
    And I select "1-slmicro61_ssh_minion_key" from "activationKeys"
    And I select the hostname of "proxy" from "proxies" if present
    And I click on "Bootstrap"
    And I wait until I see "Bootstrap process initiated." text
    And I wait until onboarding is completed for "slmicro61_ssh_minion"

@proxy
  Scenario: Check connection from SL Micro 6.1 minion to proxy
    Given I am on the Systems overview page of this "slmicro61_ssh_minion"
    When I follow "Details" in the content area
    And I follow "Connection" in the content area
    Then I should see "proxy" short hostname

@proxy
  Scenario: Check registration on proxy of SL Micro 6.1 SSH minion
    Given I am on the Systems overview page of this "proxy"
    When I follow "Details" in the content area
    And I follow "Proxy" in the content area
    Then I should see "slmicro61_ssh_minion" hostname

  Scenario: Check events history for failures on SL Micro 6.1 SSH minion
    Given I am on the Systems overview page of this "slmicro61_ssh_minion"
    Then I check for failed events on history event page
