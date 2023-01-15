# Energi Data Service Binding

This binding integrates electricity prices from the Danish Energi Data Service ("Open energy data from Energinet to society").

This can be used to plan energy consumption, for example to calculate the cheapest period for running a dishwasher or charging an EV.

## Supported Things

All channels are available for thing type `service`.

## Thing Configuration

### `service` Thing Configuration

| Name           | Type    | Description                                       | Default       | Required |
|----------------|---------|---------------------------------------------------|---------------|----------|
| priceArea      | text    | Price area for spot prices (same as bidding zone) |               | yes      |
| currencyCode   | text    | Currency code in which to obtain spot prices      | DKK           | no       |
| gridCompanyGLN | integer | Global Location Number of the Grid Company        |               | no       |
| energinetGLN   | integer | Global Location Number of Energinet               | 5790000432752 | no       |

#### Global Location Number of the Grid Company

The Global Location Number of your grid company can be selected from a built-in list of grid companies.
To find the company in your area, you can go to [Find netselskab](https://greenpowerdenmark.dk/vejledning-teknik/nettilslutning/find-netselskab), enter your address, and the company will be shown.

If your company is not on the list, you can configure it manually.
To obtain the Global Location Number of your grid company:

- Open a browser and go to [Eloverblik](https://eloverblik.dk/).
- Click "Private customers" and log in with MitID (confirmation will appear as Energinet).
- Click "Retrieve data" and select "Price data".
- Open the file and look for the rows having **Price_type** = "Subscription".
- In the columns **Name** and/or **Description** you should see the name of your grid company.
- In column **Owner** you can find the GLN ("Global Location Number").
- Most rows will have this **Owner**. If in doubt, try to look for rows __not__ having 5790000432752 as owner.

## Channels

### Channel Group `electricity`

| Channel                      | Type   | Description                                                                                    | Advanced |
|------------------------------|--------|------------------------------------------------------------------------------------------------|----------|
| currentSpotPrice             | Number | Spot price in DKK or EUR per kWh for current hour                                              | no       |
| currentNetTariff             | Number | Net tariff in DKK per kWh for current hour. Only available when `gridCompanyGLN` is configured | no       |
| currentSystemTariff          | Number | System tariff in DKK per kWh for current hour                                                  | no       |
| currentElectricityTax        | Number | Electricity tax in DKK per kWh for current hour                                                | no       |
| currentTransmissionNetTariff | Number | Transmission net tariff in DKK per kWh for current hour                                        | no       |
| futurePrices                 | String | JSON array with hourly prices from current hour and onward                                     | yes      |

_Please note:_ There is no channel providing the total price.
Instead, create a group item with `SUM` as aggregate function and add the individual price items as children.
This has the following advantages:

- Full customization possible: Freely choose the channels which should be included in the total.
- An additional item containing the kWh fee from your electricity supplier can be added also.
- Spot price can be configured in EUR while tariffs are in DKK.

#### Value-Added Tax

The channels `currentSpotPrice`, `currentNetTariff`, `currentSystemTariff`, `currentElectricityTax` and `currentTransmissionNetTariff` can be configured to include VAT with this configuration parameter:

| Name            | Type    | Description                                  | Default | Required | Advanced |
|-----------------|---------|----------------------------------------------|---------|----------|----------|
| includeVAT      | boolean | Add VAT to amount based on regional settings | no      | no       | no       |

Please be aware that this channel configuration will affect all linked items.

#### Current Net Tariff

Discounts are automatically taken into account for channel `currentNetTariff` so that it represents the actual price.

The tariffs are downloaded using pre-configured filters for the different [Grid Company GLN's](#global-location-number-of-the-grid-company).
If your company is not in the list, or the filters are not working, they can be manually overridden.
To override filters, the channel `currentNetTariff` has the following configuration parameters:

| Name            | Type    | Description                                                                                                                | Default | Required | Advanced |
|-----------------|---------|----------------------------------------------------------------------------------------------------------------------------|---------|----------|----------|
| chargeTypeCodes | text    | Comma-separated list of charge type codes                                                                                  |         | no       | yes      |
| notes           | text    | Comma-separated list of notes                                                                                              |         | no       | yes      |
| start           | text    | Query start date parameter expressed as either YYYY-MM-DD or dynamically as one of StartOfDay, StartOfMonth or StartOfYear |         | no       | yes      |

The parameters `chargeTypeCodes` and `notes` are logically combined with "AND", so if only one parameter is needed for filter, only provide this parameter and leave the other one empty.
Using any of these parameters will override the pre-configured filter entirely.

The parameter `start` can be used independently to override the query start date parameter.
If used while leaving `chargeTypeCodes` and `notes` empty, only the date will be overridden.

Determining the right filters can be tricky, so if in doubt ask in the community forum.
See also [Datahub Price List](https://www.energidataservice.dk/tso-electricity/DatahubPricelist).

##### Filter Examples

_N1:_
| Parameter       | Value      |
|-----------------|------------|
| chargeTypeCodes | CD,CD R    |
| notes           |            |

_Nord Energi Net:_
| Parameter       | Value      |
|-----------------|------------|
| chargeTypeCodes | TA031U200  |
| notes           | Nettarif C |

#### Future Prices

The format of the `futurePrices` JSON array is as follows:

```json
[
	{
		"hourStart": "2023-01-24T15:00:00Z",
		"spotPrice": 1.67076001,
		"spotPriceCurrency": "DKK",
		"netTariff": 0.432225,
		"systemTariff": 0.054000,
		"electricityTax": 0.008000,
		"transmissionNetTariff": 0.058000
	},
	{
		"hourStart": "2023-01-24T16:00:00Z",
		"spotPrice": 1.859880005,
		"spotPriceCurrency": "DKK",
		"netTariff": 1.05619,
		"systemTariff": 0.054000,
		"electricityTax": 0.008000,
		"transmissionNetTariff": 0.058000
	}
]
```

Future spot prices for the next day are usually available around 13:00 CET and are fetched around that time.
Historic prices are removed from the JSON array each hour.
Channel configuration for "Include VAT" is ignored, i.e. VAT is excluded.

## Thing Actions

Thing actions can be used to import prices directly into rules without deserializing JSON from the [futurePrices](#future-prices) channel.
This is more convenient, much faster, and provides automatic summation of the price elements of interest.

| Action name      | Input Value                   | Return Value             | Description                                                            |
|------------------|-------------------------------|--------------------------|------------------------------------------------------------------------|
| `calculatePrice` | `start` (Instant)             | BigDecimal               | Calculate price for power consumption in period excl. VAT              |
|                  | `end` (Instant)               |                          |                                                                        |
|                  | `power` (QuantityType<Power>) |                          |                                                                        |
| `getPrices`      | `priceElements` (String)      | Map<Instant, BigDecimal> | Get future prices excl. VAT as a `Map` with hourStart `Instant` as key |

### `getPrices`

The parameter `priceElements` is a case-insensitive comma-separated list of price elements to include in the returned future prices.
These elements can be requested:

| Price element         | Description             |
|-----------------------|-------------------------|
| SpotPrice             | Spot price              |
| NetTariff             | Net tariff              |
| SystemTariff          | System tariff           |
| ElectricityTax        | Electricity tax         |
| TransmissionNetTariff | Transmission net tariff |

Using `null` as parameter returns the total prices including all price elements.

## Full Example

### Thing Configuration

```java
Thing energidataservice:service:energidataservice "Energi Data Service" [ priceArea="DK1", currencyCode="DKK", gridCompanyGLN="5790001089030" ] {
    Channels:
        Number : electricity#currentSpotPrice [ includeVAT="true" ]
        Number : electricity#currentNetTariff [ includeVAT="true" ]
        Number : electricity#currentSystemTariff [ includeVAT="true" ]
        Number : electricity#currentElectricityTax [ includeVAT="true" ]
        Number : electricity#currentTransmissionNetTariff [ includeVAT="true" ]
}
```

### Item Configuration

```java
Group:Number:SUM CurrentTotalPrice "Current Total Price" <price>
Number CurrentSpotPrice "Current Spot Price" <price> (CurrentTotalPrice) {channel="energidataservice:service:energidataservice:electricity#currentSpotPrice"}
Number CurrentNetTariff "Current Net Tariff" <price> (CurrentTotalPrice) {channel="energidataservice:service:energidataservice:electricity#currentNetTariff"}
Number CurrentSystemTariff "Current System Tariff" <price> (CurrentTotalPrice) {channel="energidataservice:service:energidataservice:electricity#currentSystemTariff"}
Number CurrentElectricityTax "Current Electricity Tax" <price> (CurrentTotalPrice) {channel="energidataservice:service:energidataservice:electricity#currentElectricityTax"}
Number CurrentTransmissionNetTariff "Current Transmission Tariff" <price> (CurrentTotalPrice) {channel="energidataservice:service:energidataservice:electricity#currentTransmissionNetTariff"}
String FuturePrices "Future Prices" <price> {channel="energidataservice:service:energidataservice:electricity#futurePrices"}
```

### Thing Actions Example

```javascript
var hourStart = now.toInstant().truncatedTo(ChronoUnit.HOURS)

val actions = getActions("energidataservice", "energidataservice:service:energidataservice");

var priceMap = actions.getPrices(null);
logInfo("Current total price excl. VAT", priceMap.get(hourStart).toString)

var priceMap = actions.getPrices("SpotPrice,NetTariff");
logInfo("Current spot price + net tariff excl. VAT", priceMap.get(hourStart).toString)

var price = actions.calculatePrice(Instant.now, now.plusHours(1).toInstant, 150 | W)
logInfo("Total price for using 150 W for the next hour", price.toString)
```
