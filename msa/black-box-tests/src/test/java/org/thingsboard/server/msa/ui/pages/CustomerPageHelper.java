/**
 * Copyright © 2016-2022 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.msa.ui.pages;

import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;

@Slf4j
public class CustomerPageHelper extends CustomerPageElements {
    public CustomerPageHelper(WebDriver driver) {
        super(driver);
    }

    private String customerName;
    private String country;
    private String dashboard;
    private String dashboardFromView;

    private String customerEmail;
    private String customerCountry;
    private String customerCity;

    public void setCustomerName() {
        this.customerName = entityTitles().get(0).getText();
    }

    public void setCustomerName(int number) {
        this.customerName = entityTitles().get(number).getText();
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCountry() {
        this.country = countries().get(0).getText();
    }

    public String getCountry() {
        return country;
    }

    public void setDashboard() {
        this.dashboard = listOfEntity().get(0).getText();
    }

    public void setDashboardFromView() {
        this.dashboardFromView = editMenuDashboardField().getAttribute("value");
    }

    public String getDashboard() {
        return dashboard;
    }

    public String getDashboardFromView() {
        return dashboardFromView;
    }

    public void setCustomerEmail(String title) {
        this.customerEmail = email(title).getText();
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerCountry(String title) {
        this.customerCountry = country(title).getText();
    }

    public String getCustomerCountry() {
        return customerCountry;
    }

    public void setCustomerCity(String title) {
        this.customerCity = city(title).getText();
    }

    public String getCustomerCity() {
        return customerCity;
    }

    public void changeTitleEditMenu(String newTitle) {
        titleFieldEntityView().clear();
        wait.until(ExpectedConditions.textToBe(By.xpath(String.format(INPUT_FIELD, INPUT_FIELD_NAME_TITLE)), ""));
        titleFieldEntityView().sendKeys(newTitle);
    }

    public void chooseDashboard() {
        editMenuDashboardField().click();
        sleep(0.5);
        editMenuDashboard().click();
        sleep(0.5);
    }

    public void createCustomersUser() {
        plusBtn().click();
        addUserEmailField().sendKeys(getRandomNumber() + "@gmail.com");
        addBtnC().click();
        activateWindowOkBtn().click();
    }

    public void selectCountryEntityView() {
        countrySelectMenuEntityView().click();
        setCountry();
        countries().get(0).click();
    }

    public void selectCountryAddEntityView() {
        countrySelectMenuAddEntityView().click();
        setCountry();
        countries().get(0).click();
    }

    public void assignedDashboard() {
        plusBtn().click();
        assignedField().click();
        setDashboard();
        listOfEntity().get(0).click();
        submitAssignedBtn().click();
    }

    public boolean customerIsNotPresent(String title) {
        return elementsIsNotPresent(getEntity(title));
    }

    public void sortByNameDown() {
        doubleClick(sortByTitleBtn());
    }
}
