import Foundation

struct MapCoordinate: Equatable {
    let latitude: Double
    let longitude: Double
}

/// Ported from Android MapNavigation; stored coordinates retain their source system.
enum MapCoordinates {
    static func toGCJ(_ coordinate: MapCoordinate, system: String) -> MapCoordinate {
        let lat = coordinate.latitude, lon = coordinate.longitude
        if system == "bd09" {
            let x = lon - 0.0065, y = lat - 0.006
            let z = sqrt(x * x + y * y) - 0.00002 * sin(y * .pi * 3000 / 180)
            let theta = atan2(y, x) - 0.000003 * cos(x * .pi * 3000 / 180)
            return MapCoordinate(latitude: z * sin(theta), longitude: z * cos(theta))
        }
        guard system == "wgs84", !outsideChina(coordinate) else { return coordinate }
        let x = lon - 105, y = lat - 35
        var dlat = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        var dlon = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        dlat += (20 * sin(6 * x * .pi) + 20 * sin(2 * x * .pi)) * 2 / 3
        dlat += (20 * sin(y * .pi) + 40 * sin(y / 3 * .pi)) * 2 / 3
        dlat += (160 * sin(y / 12 * .pi) + 320 * sin(y * .pi / 30)) * 2 / 3
        dlon += (20 * sin(6 * x * .pi) + 20 * sin(2 * x * .pi)) * 2 / 3
        dlon += (20 * sin(x * .pi) + 40 * sin(x / 3 * .pi)) * 2 / 3
        dlon += (150 * sin(x / 12 * .pi) + 300 * sin(x / 30 * .pi)) * 2 / 3
        let rad = lat / 180 * .pi
        let magic = 1 - 0.00669342162296594323 * sin(rad) * sin(rad)
        dlat = dlat * 180 / ((6378245 * (1 - 0.00669342162296594323)) / (magic * sqrt(magic)) * .pi)
        dlon = dlon * 180 / (6378245 / sqrt(magic) * cos(rad) * .pi)
        return MapCoordinate(latitude: lat + dlat, longitude: lon + dlon)
    }
    static func toWGS(_ coordinate: MapCoordinate, system: String) -> MapCoordinate {
        guard system != "wgs84" else { return coordinate }
        let gcj = toGCJ(coordinate, system: system)
        guard !outsideChina(gcj) else { return gcj }
        var current = gcj
        for _ in 0..<5 {
            let converted = toGCJ(current, system: "wgs84")
            current = MapCoordinate(latitude: current.latitude - (converted.latitude - gcj.latitude), longitude: current.longitude - (converted.longitude - gcj.longitude))
        }
        return current
    }
    private static func outsideChina(_ c: MapCoordinate) -> Bool { c.longitude < 72.004 || c.longitude > 137.8347 || c.latitude < 0.8293 || c.latitude > 55.8271 }
}
