import SwiftUI
import MapKit

struct ShopNavigationButton: View {
    let shop: Record
    @Environment(\.openURL) private var openURL
    @State private var showing = false
    private var coordinate: MapCoordinate? {
        guard let lat = Double(shop["latitude"].string), let lon = Double(shop["longitude"].string), (-90...90).contains(lat), (-180...180).contains(lon), lat.isFinite, lon.isFinite else { return nil }
        return MapCoordinate(latitude: lat, longitude: lon)
    }
    var body: some View {
        Button("门店导航", systemImage: "location.circle") { showing = true }.disabled(coordinate == nil && shop["address"].string.isEmpty)
            .confirmationDialog("导航到 " + shop.name, isPresented: $showing, titleVisibility: .visible) {
                Button("Apple 地图") { appleMaps() }
                Button("高德网页地图") { webMaps() }
                if !shop["address"].string.isEmpty { Button("复制门店地址") { UIPasteboard.general.string = shop["address"].string } }
            }
    }
    private func appleMaps() {
        if let coordinate {
            let wgs = MapCoordinates.toWGS(coordinate, system: shop["coordinate_system"].string.isEmpty ? "gcj02" : shop["coordinate_system"].string)
            let item = MKMapItem(placemark: MKPlacemark(coordinate: CLLocationCoordinate2D(latitude: wgs.latitude, longitude: wgs.longitude))); item.name = shop.name
            item.openInMaps(launchOptions: [MKLaunchOptionsDirectionsModeKey: MKLaunchOptionsDirectionsModeDriving])
        } else { var url = URLComponents(string: "https://maps.apple.com/")!; url.queryItems = [URLQueryItem(name: "q", value: shop["address"].string)]; if let url = url.url { openURL(url) } }
    }
    private func webMaps() {
        var url = URLComponents(string: coordinate == nil ? "https://uri.amap.com/search" : "https://uri.amap.com/marker")!
        if let coordinate { let gcj = MapCoordinates.toGCJ(coordinate, system: shop["coordinate_system"].string.isEmpty ? "gcj02" : shop["coordinate_system"].string); url.queryItems = [URLQueryItem(name: "position", value: String(gcj.longitude) + "," + String(gcj.latitude)), URLQueryItem(name: "name", value: shop.name), URLQueryItem(name: "coordinate", value: "gaode"), URLQueryItem(name: "callnative", value: "0")] }
        else { url.queryItems = [URLQueryItem(name: "keyword", value: shop["address"].string)] }
        if let url = url.url { openURL(url) }
    }
}
